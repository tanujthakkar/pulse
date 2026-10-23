package app.pulse.wire.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChecksumRejectionTest {
    @Test
    fun corruptHeaderCrcIsRejected() {
        val flippedTrailer = HelloCommand.bytes()
        flippedTrailer[6] = (flippedTrailer[6].toInt() xor 0xFF).toByte()
        assertEquals(RejectReason.BAD_HEADER_CRC, FrameCodec.decode(flippedTrailer).rejected())

        val flippedCoveredByte = HelloCommand.bytes()
        flippedCoveredByte[4] = (flippedCoveredByte[4].toInt() xor 0x01).toByte()
        assertEquals(RejectReason.BAD_HEADER_CRC, FrameCodec.decode(flippedCoveredByte).rejected())

        val accumulator = FrameAccumulator()
        assertReject(RejectReason.BAD_HEADER_CRC, accumulator.append(flippedTrailer))
        assertEquals(0, accumulator.bufferedByteCount)
    }

    @Test
    fun corruptBodyCrcIsRejected() {
        val bytes = HelloCommand.bytes()
        bytes[15] = (bytes[15].toInt() xor 0xFF).toByte()
        assertEquals(RejectReason.BAD_BODY_CRC, FrameCodec.decode(bytes).rejected())

        val accumulator = FrameAccumulator()
        val events = accumulator.append(bytes)
        assertReject(RejectReason.BAD_BODY_CRC, events)
        assertEquals(0, accumulator.bufferedByteCount)
    }

    @Test
    fun bodyCrcFailureDoesNotSwallowTheNextFrame() {
        val bad = HelloCommand.bytes()
        bad[15] = (bad[15].toInt() xor 0xFF).toByte()
        val good = HelloCommand.bytes(sequence = 3)
        val accumulator = FrameAccumulator()
        val events = accumulator.append(bad + good)
        assertEquals(listOf(RejectReason.BAD_BODY_CRC), events.filterIsInstance<AccumulatorEvent.Rejected>().map { it.reason })
        val frames = events.frames()
        assertEquals(1, frames.size)
        assertEquals(3, frames[0].sequence)
        assertEquals(0, accumulator.bufferedByteCount)
    }
}
