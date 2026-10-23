package app.pulse.wire.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrameLogTest {
    @Test
    fun ringKeepsTheNewestFramesAndNotifiesWatchers() {
        val ring = FrameRing(capacity = 2)
        var seen = 0
        val stop = ring.watch { seen += 1 }
        val intake = FrameIntake(ring)
        intake.offer(incoming = false, characteristic = "fd4b0002", framed = true, payload = HelloCommand.bytes(1), atMillis = 1)
        intake.offer(incoming = false, characteristic = "fd4b0002", framed = true, payload = HelloCommand.bytes(2), atMillis = 2)
        intake.offer(incoming = false, characteristic = "fd4b0002", framed = true, payload = HelloCommand.bytes(3), atMillis = 3)
        val notes = ring.snapshot()
        assertEquals(2, notes.size)
        assertEquals(3L, notes[0].atMillis)
        assertEquals(2L, notes[1].atMillis)
        assertTrue(notes[0].id > notes[1].id)
        assertFalse(notes[0].incoming)
        assertEquals(3, seen)
        stop()
        intake.offer(incoming = false, characteristic = "fd4b0002", framed = true, payload = HelloCommand.bytes(4), atMillis = 4)
        assertEquals(3, seen)
        assertEquals(4L, ring.snapshot().first().atMillis)
    }

    @Test
    fun pauseDropsNotesUntilRecordingResumes() {
        val ring = FrameRing(capacity = 4)
        var ticks = 0
        ring.watch { ticks += 1 }
        val intake = FrameIntake(ring)
        ring.setPaused(true)
        intake.offer(incoming = true, characteristic = "2a37", framed = false, payload = byteArrayOf(1), atMillis = 1)
        assertEquals(0, ring.size())
        assertEquals(0, ticks)
        ring.setPaused(false)
        intake.offer(incoming = true, characteristic = "2a37", framed = false, payload = byteArrayOf(2), atMillis = 2)
        assertEquals(1, ring.size())
        assertEquals(1, ticks)
    }

    @Test
    fun defaultCapacityIsFiveThousand() {
        assertEquals(5_000, FrameRing.CAPACITY)
        val ring = FrameRing()
        val intake = FrameIntake(ring)
        repeat(FrameRing.CAPACITY + 1) { index ->
            intake.offer(
                incoming = true,
                characteristic = "2a37",
                framed = false,
                payload = byteArrayOf((index and 0xFF).toByte()),
                atMillis = index.toLong(),
            )
        }
        assertEquals(FrameRing.CAPACITY, ring.size())
        assertEquals(FrameRing.CAPACITY.toLong(), ring.snapshot().first().atMillis)
        assertEquals(1L, ring.snapshot().last().atMillis)
    }

    @Test
    fun capacityMustBePositive() {
        val error = assertThrows(IllegalArgumentException::class.java) { FrameRing(0) }
        assertTrue(error.message!!.contains("positive"))
    }

    @Test
    fun emptyPayloadIsIgnored() {
        val ring = FrameRing()
        FrameIntake(ring).offer(true, "fd4b0003", true, byteArrayOf(), 1)
        assertEquals(0, ring.size())
    }

    @Test
    fun outboundCommandAndUnframedNotificationAreLabeled() {
        val ring = FrameRing()
        val intake = FrameIntake(ring)
        intake.offer(false, "fd4b0002", true, HelloCommand.bytes(), 10)
        intake.offer(true, "2a37", false, byteArrayOf(0x00, 0x48), 11)
        val notes = ring.snapshot()
        assertEquals("2a37", notes[0].characteristic)
        assertTrue(notes[0].incoming)
        assertNull(notes[0].packetType)
        assertEquals("—", notes[0].marker)
        assertEquals(FrameVerdict.NOT_A_FRAME, notes[0].crc)
        assertEquals("00 48", notes[0].hex)
        assertEquals(2, notes[0].size)
        assertEquals("fd4b0002", notes[1].characteristic)
        assertFalse(notes[1].incoming)
        assertEquals(PacketType.COMMAND, notes[1].packetType)
        assertEquals("opcode 145", notes[1].marker)
        assertEquals(FrameVerdict.OK, notes[1].crc)
        assertEquals(16, notes[1].size)
        assertFalse(notes[1].hex.contains(HelloRedaction.MARKER))
    }

    @Test
    fun splitNotificationBecomesOneInboundFrame() {
        val ring = FrameRing()
        val intake = FrameIntake(ring)
        val hello = HelloCommand.bytes()
        intake.offer(true, "fd4b0003", true, hello.copyOfRange(0, 7), 1)
        assertEquals(0, ring.size())
        intake.offer(true, "fd4b0003", true, hello.copyOfRange(7, hello.size), 2)
        val note = ring.snapshot().single()
        assertTrue(note.incoming)
        assertEquals("fd4b0003", note.characteristic)
        assertEquals(PacketType.COMMAND, note.packetType)
        assertEquals(FrameVerdict.OK, note.crc)
        assertEquals(hello.size, note.size)
        assertEquals(2L, note.atMillis)
    }

    @Test
    fun separateIntakesDoNotContinueAPartialFrame() {
        val ring = FrameRing()
        val hello = HelloCommand.bytes()
        val first = FrameIntake(ring)
        val second = FrameIntake(ring)
        first.offer(true, "fd4b0003", true, hello.copyOfRange(0, 6), 1)
        second.offer(true, "fd4b0003", true, hello.copyOfRange(6, hello.size), 2)
        assertEquals(0, ring.size())
        first.offer(true, "fd4b0003", true, hello.copyOfRange(6, hello.size), 3)
        assertEquals(1, ring.size())
    }

    @Test
    fun characteristicsReassembleApart() {
        val ring = FrameRing()
        val intake = FrameIntake(ring)
        val hello = HelloCommand.bytes()
        intake.offer(true, "fd4b0003", true, hello.copyOfRange(0, 4), 1)
        intake.offer(true, "fd4b0004", true, hello, 2)
        assertEquals("fd4b0004", ring.snapshot().single().characteristic)
        intake.offer(true, "fd4b0003", true, hello.copyOfRange(4, hello.size), 3)
        assertEquals(2, ring.size())
        assertEquals("fd4b0003", ring.snapshot().first().characteristic)
    }

    @Test
    fun garbageBeforeAFrameIsNotStored() {
        val ring = FrameRing()
        val hello = HelloCommand.bytes()
        FrameIntake(ring).offer(true, "fd4b0005", true, byteArrayOf(0x00, 0x10) + hello, 4)
        val note = ring.snapshot().single()
        assertEquals(FrameVerdict.OK, note.crc)
        assertEquals(hello.size, note.size)
    }

    @Test
    fun headerCrcFailureIsOneShortNote() {
        val ring = FrameRing()
        val bad = HelloCommand.bytes()
        bad[6] = (bad[6].toInt() xor 0xFF).toByte()
        FrameIntake(ring).offer(true, "fd4b0003", true, bad, 8)
        val note = ring.snapshot().single()
        assertEquals("header crc", note.crc)
        assertEquals(8, note.size)
        assertNull(note.packetType)
        assertEquals("—", note.marker)
        assertFalse(note.hex.contains(HelloRedaction.MARKER))
    }

    @Test
    fun outboundFailuresKeepThePayloadVerdict() {
        val ring = FrameRing()
        val intake = FrameIntake(ring)
        val badFormat = HelloCommand.bytes()
        badFormat[1] = 0x02
        intake.offer(false, "fd4b0002", true, badFormat, 1)
        intake.offer(false, "fd4b0002", true, byteArrayOf(1, 2, 3), 2)
        val notes = ring.snapshot()
        assertEquals("too short", notes[0].crc)
        assertNull(notes[0].packetType)
        assertEquals("bad format", notes[1].crc)
    }

    @Test
    fun markerLabelsFollowThePacket() {
        assertEquals("opcode 26", markerLabel(PacketType.COMMAND, 1, 26))
        assertEquals("opcode 145", markerLabel(PacketType.COMMAND_RESPONSE, 2, 145))
        assertEquals("layout 18", markerLabel(43, 18, 0))
        assertEquals("layout 21", markerLabel(PacketType.HISTORY_RECORD, 21, 0))
        assertEquals("event 7", markerLabel(PacketType.EVENT, 1, 7))
        assertEquals("kind 2", markerLabel(PacketType.HISTORY_METADATA, 1, 2))
        assertEquals("b10 4", markerLabel(PacketType.CONSOLE, 1, 4))
        assertEquals("b10 9", markerLabel(55, 1, 9))

        val ring = FrameRing()
        val intake = FrameIntake(ring)
        intake.offer(false, "fd4b0002", true, packet(43, at9 = 18, at10 = 0), 1)
        intake.offer(false, "fd4b0002", true, packet(PacketType.HISTORY_RECORD, at9 = 21, at10 = 3), 2)
        intake.offer(false, "fd4b0002", true, packet(PacketType.EVENT, at9 = 1, at10 = 7), 3)
        intake.offer(false, "fd4b0002", true, packet(PacketType.HISTORY_METADATA, at9 = 1, at10 = 2), 4)
        intake.offer(false, "fd4b0002", true, packet(PacketType.CONSOLE, at9 = 1, at10 = 4), 5)
        val markers = ring.snapshot().map { it.marker }
        assertEquals(listOf("b10 4", "kind 2", "event 7", "layout 21", "layout 18"), markers)
    }

    @Test
    fun crcLabelsCoverLocalFailures() {
        assertEquals("header crc", crcLabel(RejectReason.BAD_HEADER_CRC))
        assertEquals("body crc", crcLabel(RejectReason.BAD_BODY_CRC))
        assertEquals("too short", crcLabel(RejectReason.TOO_SHORT))
        assertEquals("bad start", crcLabel(RejectReason.BAD_START))
        assertEquals("bad format", crcLabel(RejectReason.BAD_FORMAT))
        assertEquals("too large", crcLabel(RejectReason.FRAME_TOO_LARGE))
        assertEquals("length", crcLabel(RejectReason.LENGTH_MISMATCH))
        assertEquals("alignment", crcLabel(RejectReason.LENGTH_ALIGNMENT))
    }

    @Test
    fun helloIdentityBlocksAreRedactedAndOtherFramesAreNot() {
        val frame = helloResponse()
        val hex = HelloRedaction.hex(frame)
        assertIdentityHidden(frame, hex)
        assertTrue(hex.contains("32 2a 01"))
        assertFalse(hex.contains("SECRETBLOCK"))

        val ring = FrameRing()
        FrameIntake(ring).offer(true, "fd4b0003", true, frame, 20)
        val note = ring.snapshot().single()
        assertEquals(PacketType.COMMAND_RESPONSE, note.packetType)
        assertEquals("opcode 145", note.marker)
        assertEquals(FrameVerdict.OK, note.crc)
        assertIdentityHidden(frame, note.hex)
        assertFalse(note.toString().contains("SECRETBLOCK"))

        val kept = FrameCodec.encode(
            headerWord = HeaderWord.COMMON_REQUEST,
            packetType = PacketType.COMMAND,
            sequence = 1,
            command = 26,
            body = byteArrayOf(0x7E.toByte()),
        )
        assertTrue(HelloRedaction.hex(kept).contains("7e"))
        assertFalse(HelloRedaction.hex(kept).contains(HelloRedaction.MARKER))
    }

    @Test
    fun corruptHelloResponseStillHidesIdentity() {
        val frame = helloResponse()
        frame[frame.lastIndex] = (frame[frame.lastIndex].toInt() xor 0xFF).toByte()
        val ring = FrameRing()
        FrameIntake(ring).offer(true, "fd4b0003", true, frame, 21)
        val note = ring.snapshot().single()
        assertEquals("body crc", note.crc)
        assertEquals("opcode 145", note.marker)
        assertIdentityHidden(frame, note.hex)
    }

    @Test
    fun shortHelloResponseHasNoIdentityBytesToHide() {
        val frame = FrameCodec.encodeCommandResponse(
            sequence = 1,
            command = HelloCommand.NUMBER,
            originSequence = 1,
            result = CommandResult.SUCCESS,
            body = byteArrayOf(HelloCommand.REVISION.toByte()),
        )
        val hex = HelloRedaction.hex(frame)
        assertFalse(hex.contains(HelloRedaction.MARKER))
        val ring = FrameRing()
        FrameIntake(ring).offer(false, "fd4b0002", true, frame, 1)
        assertEquals(FrameVerdict.OK, ring.snapshot().single().crc)
        assertFalse(ring.snapshot().single().hex.contains(HelloRedaction.MARKER))
    }

    @Test
    fun recordingAHistoryAckDoesNotOpenTheGate() {
        assertFalse(HistoryAckGate.maySend())
        assertFalse(HistoryAckGate.debugRequested)
        val ring = FrameRing()
        val frame = FrameCodec.encode(
            headerWord = HeaderWord.COMMON_REQUEST,
            packetType = PacketType.COMMAND,
            sequence = 1,
            command = HistoryAckGate.NUMBER,
            body = byteArrayOf(0x01, 1, 2, 3, 4, 5, 6, 7, 8),
        )
        FrameIntake(ring).offer(false, "fd4b0002", true, frame, 5)
        assertEquals("opcode 23", ring.snapshot().single().marker)
        assertEquals(FrameVerdict.OK, ring.snapshot().single().crc)
        assertFalse(HistoryAckGate.maySend())
        assertFalse(HistoryAckGate.debugRequested)
        assertFalse(HistoryAckGate.OFFSETS_CONFIRMED)
    }

    private fun packet(type: Int, at9: Int, at10: Int): ByteArray = FrameCodec.encode(
        headerWord = HeaderWord.COMMON_REQUEST,
        packetType = type,
        sequence = at9,
        command = at10,
        body = byteArrayOf(0x01),
    )
}

private fun assertIdentityHidden(frame: ByteArray, hex: String) {
    val hidden = HelloBody.IDENTITY.flatMap { range -> range.map { 13 + it } }.toSet()
    assertEquals(11 + 24 + 30, hidden.size)
    assertTrue(hidden.all { it < frame.size })
    val outside = frame.indices.filter { it !in hidden }.map { frame[it].toInt() and 0xFF }.toSet()
    val exclusive = hidden.map { frame[it].toInt() and 0xFF }.toSet() - outside
    assertTrue(exclusive.isNotEmpty(), "identity bytes also appear outside the blocks")
    val tokens = hex.split(' ')
    exclusive.forEach { value ->
        assertFalse(tokens.any { it == "%02x".format(value) }, "leaked %02x".format(value))
    }
    assertEquals(3, hex.split(HelloRedaction.MARKER).size - 1)
    assertFalse(hex.contains("SECRETBLOCK"))
}

private fun helloResponse(): ByteArray {
    val body = ByteArray(HelloBody.FINAL_LENGTH)
    body[0] = HelloCommand.REVISION.toByte()
    body[13] = 0x11
    val word = "SECRETBLOCK".toByteArray(Charsets.US_ASCII)
    for (range in HelloBody.IDENTITY) {
        for (index in range) body[index] = word[(index - range.first) % word.size]
    }
    body[79] = 0x22
    body[HelloBody.VERSION_OFFSET] = 50
    body[HelloBody.VERSION_OFFSET + 1] = 42
    body[HelloBody.VERSION_OFFSET + 2] = 1
    return FrameCodec.encodeCommandResponse(
        sequence = 2,
        command = HelloCommand.NUMBER,
        originSequence = 1,
        result = CommandResult.SUCCESS,
        body = body,
    )
}
