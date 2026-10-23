package app.pulse.wire.codec

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HelloAndBatteryTest {
    @Test
    fun denyListMatchesTheSpecAndHasNoOverride() {
        assertEquals(
            setOf(25, 29, 32, 33, 36, 37, 38, 45, 99, 107, 119, 120, 142, 143, 144, 153, 154),
            DeniedCommands.NUMBERS,
        )
        assertEquals(155..159, DeniedCommands.CERTIFICATE)
        DeniedCommands.NUMBERS.forEach { command ->
            assertFalse(DeniedCommands.allows(command), "command $command")
            assertThrows(IllegalArgumentException::class.java) {
                CommandFrames.request(command, sequence = 1, body = ByteArray(0))
            }
        }
        DeniedCommands.CERTIFICATE.forEach { command ->
            assertFalse(DeniedCommands.allows(command))
        }
        assertTrue(DeniedCommands.allows(HelloCommand.NUMBER))
        assertTrue(DeniedCommands.allows(BatteryCommand.NUMBER))
        assertFalse(DeniedCommands.allows(-1))
        assertFalse(DeniedCommands.allows(256))
    }

    @Test
    fun historyAckStaysOffUntilMetadataOffsetsAreConfirmed() {
        HistoryAckGate.debugRequested = false
        assertFalse(HistoryAckGate.OFFSETS_CONFIRMED)
        assertFalse(HistoryAckGate.maySend())
        assertFalse(DeniedCommands.allows(HistoryAckGate.NUMBER))
        HistoryAckGate.debugRequested = true
        try {
            assertFalse(HistoryAckGate.maySend())
            assertFalse(DeniedCommands.allows(HistoryAckGate.NUMBER))
            assertThrows(IllegalArgumentException::class.java) {
                CommandFrames.request(HistoryAckGate.NUMBER, sequence = 1, body = byteArrayOf(0x01))
            }
        } finally {
            HistoryAckGate.debugRequested = false
        }
    }

    @Test
    fun documentedHelloReachesFinalSuccessOnTheRequestTracker() {
        val request = HelloCommand.bytes()
        assertEquals("AA0108000001E67123019101363E5C8D", request.toHex())
        val decoded = FrameCodec.decode(request).ok()
        val connection = ConnectionId(7)
        val tracker = RequestTracker()
        assertTrue(tracker.open(connection, decoded.command, decoded.sequence))

        val pending = FrameCodec.encodeCommandResponse(
            sequence = 4,
            command = HelloCommand.NUMBER,
            originSequence = HelloCommand.DOCUMENTED_SEQUENCE,
            result = CommandResult.PENDING,
            body = pendingHelloBody(),
        )
        val pendingMatch = tracker.onFrame(connection, FrameCodec.decode(pending).ok())
        assertTrue(pendingMatch is ReplyMatch.Matched)
        assertTrue((pendingMatch as ReplyMatch.Matched).pending)
        assertEquals(1, tracker.openCount)

        val finalBody = helloBody(major = 50, minor = 42, patch = 1, suffix = 0)
        val finalFrame = FrameCodec.encodeCommandResponse(
            sequence = 5,
            command = HelloCommand.NUMBER,
            originSequence = HelloCommand.DOCUMENTED_SEQUENCE,
            result = CommandResult.SUCCESS,
            body = finalBody,
        )
        val match = tracker.onFrame(connection, FrameCodec.decode(finalFrame).ok())
        assertTrue(match is ReplyMatch.Matched)
        val matched = match as ReplyMatch.Matched
        assertTrue(matched.succeeded)
        assertFalse(matched.pending)
        assertEquals(0, tracker.openCount)

        val response = FrameCodec.decode(finalFrame).ok().toCommandResponse()
        val parsed = HelloBody.parse(checkNotNull(response).body)
        assertEquals(1, parsed?.revision)
        assertEquals("50.42.1.0", parsed?.versionText)
    }

    @Test
    fun helloIdentityBlocksAreRedactedAndOmittedFromTheSummary() {
        val marker = "SECRET-IDENTITY-BLOCK-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
        val body = helloBody(major = 50, minor = 42, patch = 1, suffix = 0)
        marker.encodeToByteArray().copyInto(body, destinationOffset = 14)
        val summary = HelloBody.summary(body)
        assertFalse(summary.contains("SECRET"))
        assertFalse(summary.contains("IDENTITY"))
        assertTrue(summary.contains("identity=redacted"))
        assertTrue(summary.contains("version=50.42.1.0"))
        assertTrue(summary.contains("revision=1"))

        val redacted = HelloBody.redact(body)
        for (range in HelloBody.IDENTITY) {
            for (index in range) {
                assertEquals(0, redacted[index].toInt())
            }
        }
        assertEquals("50.42.1.0", HelloBody.parse(redacted)?.versionText)
        assertEquals(HelloCommand.REVISION, redacted[0].toInt())
        assertArrayEquals(body.copyOfRange(0, 14), redacted.copyOfRange(0, 14))
    }

    @Test
    fun shortHelloBodyKeepsRevisionAndOmitsVersion() {
        assertNull(HelloBody.parse(ByteArray(0)))
        val parsed = HelloBody.parse(byteArrayOf(0x01, 0x02))
        assertEquals(1, parsed?.revision)
        assertNull(parsed?.versionText)
        val redacted = HelloBody.redact(byteArrayOf(0x41, 0x42))
        assertArrayEquals(byteArrayOf(0x41, 0x42), redacted)
    }

    @Test
    fun batteryPercentIsTheLeadingUnsignedWord() {
        assertEquals(0, BatteryCommand.REQUEST_BODY.size)
        assertNull(BatteryCommand.percent(byteArrayOf(1, 2, 3)))
        assertEquals(80L, BatteryCommand.percent(byteArrayOf(80, 0, 0, 0, 0)))
        assertEquals(0L, BatteryCommand.percent(byteArrayOf(0, 0, 0, 0)))
        val frame = CommandFrames.request(
            command = BatteryCommand.NUMBER,
            sequence = 2,
            body = BatteryCommand.REQUEST_BODY,
        )
        val decoded = FrameCodec.decode(frame).ok()
        assertEquals(BatteryCommand.NUMBER, decoded.command)
        assertEquals(2, decoded.sequence)
        assertEquals(PacketType.COMMAND, decoded.packetType)
    }

    private fun pendingHelloBody(): ByteArray {
        val body = ByteArray(HelloBody.FINAL_LENGTH)
        body[0] = HelloCommand.REVISION.toByte()
        return body
    }

    private fun helloBody(major: Int, minor: Int, patch: Int, suffix: Int): ByteArray {
        val body = ByteArray(HelloBody.FINAL_LENGTH)
        body[0] = HelloCommand.REVISION.toByte()
        body[HelloBody.VERSION_OFFSET] = major.toByte()
        body[HelloBody.VERSION_OFFSET + 1] = minor.toByte()
        body[HelloBody.VERSION_OFFSET + 2] = patch.toByte()
        body[HelloBody.SUFFIX_OFFSET] = (suffix and 0xFF).toByte()
        body[HelloBody.SUFFIX_OFFSET + 1] = ((suffix ushr 8) and 0xFF).toByte()
        body[HelloBody.SUFFIX_OFFSET + 2] = ((suffix ushr 16) and 0xFF).toByte()
        body[HelloBody.SUFFIX_OFFSET + 3] = ((suffix ushr 24) and 0xFF).toByte()
        return body
    }
}
