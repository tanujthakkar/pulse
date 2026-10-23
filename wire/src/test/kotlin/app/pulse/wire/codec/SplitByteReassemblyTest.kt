package app.pulse.wire.codec

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SplitByteReassemblyTest {
    @Test
    fun helloSplitAtEveryByteReassemblesOnce() {
        val bytes = HelloCommand.bytes()
        val accumulator = FrameAccumulator()
        val frames = ArrayList<WireFrame>()
        bytes.forEachIndexed { index, byte ->
            val events = accumulator.append(byteArrayOf(byte))
            frames += events.frames()
            if (index < bytes.lastIndex) {
                assertTrue(events.none { it is AccumulatorEvent.Frame })
                assertEquals(index + 1, accumulator.bufferedByteCount)
            }
        }
        assertEquals(1, frames.size)
        assertArrayEquals(bytes, frames[0].bytes)
        assertEquals(0, accumulator.bufferedByteCount)
    }

    @Test
    fun twoPaddedFramesSplitAtEveryByteStayInOrder() {
        val first = command(sequence = 1, body = byteArrayOf(0x11, 0x00))
        val second = command(sequence = 2, body = byteArrayOf(0x22, 0x33, 0x44))
        val accumulator = FrameAccumulator()
        val frames = ArrayList<WireFrame>()
        for (byte in first + second) {
            frames += accumulator.append(byteArrayOf(byte)).frames()
        }
        assertEquals(2, frames.size)
        assertArrayEquals(first, frames[0].bytes)
        assertArrayEquals(second, frames[1].bytes)
        assertEquals(5, frames[0].tail.size)
        assertEquals(0x11, frames[0].tail[0].toInt() and 0xFF)
        assertEquals(0x00, frames[0].tail[1].toInt() and 0xFF)
    }

    @Test
    fun oneChunkMayHoldSeveralFramesAndAPartialTail() {
        val first = HelloCommand.bytes()
        val second = HelloCommand.bytes(sequence = 9)
        val accumulator = FrameAccumulator()
        val head = accumulator.append(first + second.copyOfRange(0, 7))
        assertEquals(1, head.frames().size)
        assertArrayEquals(first, head.frames()[0].bytes)
        assertEquals(7, accumulator.bufferedByteCount)
        val rest = accumulator.append(second.copyOfRange(7, second.size))
        assertEquals(1, rest.frames().size)
        assertEquals(9, rest.frames()[0].sequence)
        assertEquals(0, accumulator.bufferedByteCount)
    }

    @Test
    fun garbageBeforeAFrameIsALocalFailureAndTheFrameStillDecodes() {
        val hello = HelloCommand.bytes()
        val accumulator = FrameAccumulator()
        val events = accumulator.append(byteArrayOf(0x00, 0x10) + hello)
        assertEquals(listOf(RejectReason.BAD_START), events.filterIsInstance<AccumulatorEvent.Rejected>().map { it.reason })
        assertArrayEquals(hello, events.frames().single().bytes)
    }

    @Test
    fun trailingStartByteAfterGarbageIsHeld() {
        val hello = HelloCommand.bytes()
        val accumulator = FrameAccumulator()
        val held = accumulator.append(byteArrayOf(0x00, FrameBytes.START))
        assertEquals(
            listOf(RejectReason.BAD_START),
            held.filterIsInstance<AccumulatorEvent.Rejected>().map { it.reason },
        )
        assertEquals(1, accumulator.bufferedByteCount)
        assertArrayEquals(hello, accumulator.append(hello.copyOfRange(1, hello.size)).frames().single().bytes)
    }

    @Test
    fun loneStartByteIsHeldUntilTheNextChunk() {
        val hello = HelloCommand.bytes()
        val accumulator = FrameAccumulator()
        val held = accumulator.append(byteArrayOf(FrameBytes.START))
        assertTrue(held.isEmpty())
        assertEquals(1, accumulator.bufferedByteCount)
        val frames = accumulator.append(hello.copyOfRange(1, hello.size)).frames()
        assertArrayEquals(hello, frames.single().bytes)
    }

    @Test
    fun falseStartByteDoesNotHideTheRealFrame() {
        val hello = HelloCommand.bytes()
        val frames = FrameAccumulator().append(byteArrayOf(FrameBytes.START) + hello).frames()
        assertArrayEquals(hello, frames.single().bytes)
    }

    @Test
    fun resetDropsAPartialFrame() {
        val hello = HelloCommand.bytes()
        val accumulator = FrameAccumulator()
        assertTrue(accumulator.append(hello.copyOfRange(0, 10)).isEmpty())
        accumulator.reset()
        assertEquals(0, accumulator.bufferedByteCount)
        val frames = accumulator.append(hello).frames()
        assertArrayEquals(hello, frames.single().bytes)
    }

    @Test
    fun emptyAppendDoesNothing() {
        val accumulator = FrameAccumulator()
        assertTrue(accumulator.append(byteArrayOf()).isEmpty())
        assertEquals(0, accumulator.bufferedByteCount)
    }

    @Test
    fun oversizeDeclaredLengthDoesNotBlockTheFollowingFrame() {
        val claim = byteArrayOf(FrameBytes.START, FrameBytes.FORMAT, 0xFF.toByte(), 0x7F)
        val hello = HelloCommand.bytes()
        val events = FrameAccumulator().append(claim + hello)
        val reasons = events.filterIsInstance<AccumulatorEvent.Rejected>().map { it.reason }
        assertTrue(reasons.contains(RejectReason.FRAME_TOO_LARGE))
        assertArrayEquals(hello, events.frames().single().bytes)
    }

    @Test
    fun misalignedDeclaredLengthIsRejected() {
        val claim = byteArrayOf(FrameBytes.START, FrameBytes.FORMAT, 0x09, 0x00, 0, 0, 0, 0)
        val events = FrameAccumulator().append(claim)
        assertTrue(events.filterIsInstance<AccumulatorEvent.Rejected>().any { it.reason == RejectReason.LENGTH_ALIGNMENT })
        assertTrue(events.frames().isEmpty())
    }

    @Test
    fun declaredLengthBelowTheMinimumIsRejected() {
        val claim = byteArrayOf(FrameBytes.START, FrameBytes.FORMAT, 0x00, 0x00)
        val events = FrameAccumulator().append(claim + HelloCommand.bytes())
        assertTrue(events.filterIsInstance<AccumulatorEvent.Rejected>().any { it.reason == RejectReason.TOO_SHORT })
        assertArrayEquals(HelloCommand.bytes(), events.frames().single().bytes)
    }

    @Test
    fun maximumFramedRecordFitsAndReassembles() {
        val body = ByteArray(2125) { index -> (index and 0xFF).toByte() }
        val frame = FrameCodec.encode(
            headerWord = 0xABCD,
            packetType = 47,
            sequence = 8,
            command = 0,
            body = body,
        )
        assertEquals(WireLimits.MAX_FRAME_BYTES, frame.size)
        val accumulator = FrameAccumulator()
        val split = frame.size / 2
        assertTrue(accumulator.append(frame.copyOfRange(0, split)).frames().isEmpty())
        val decoded = accumulator.append(frame.copyOfRange(split, frame.size)).frames().single()
        assertEquals(47, decoded.packetType)
        assertEquals(0xABCD, decoded.headerWord)
        assertArrayEquals(body, decoded.tail)
    }

    @Test
    fun accumulatorRejectsCapsOutsideTheSpecBound() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            FrameAccumulator(maxFrameBytes = 15)
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            FrameAccumulator(maxFrameBytes = WireLimits.MAX_FRAME_BYTES + 1)
        }
    }

    private fun command(sequence: Int, body: ByteArray): ByteArray =
        FrameCodec.encode(
            headerWord = HeaderWord.COMMON_REQUEST,
            packetType = PacketType.COMMAND,
            sequence = sequence,
            command = 22,
            body = body,
        )
}
