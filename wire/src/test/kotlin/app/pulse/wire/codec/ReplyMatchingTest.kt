package app.pulse.wire.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplyMatchingTest {
    private val connection = ConnectionId(1)
    private val other = ConnectionId(2)

    @Test
    fun pendingKeepsTheRequestOpenForALaterResult() {
        val tracker = RequestTracker()
        assertTrue(tracker.open(connection, HelloCommand.NUMBER, sequence = 1))
        assertFalse(tracker.open(connection, HelloCommand.NUMBER, sequence = 1))
        assertEquals(1, tracker.openCount)

        val pending = match(
            tracker.accept(connection, response(responseSequence = 4, origin = 1, result = CommandResult.PENDING)),
        )
        assertTrue(pending.pending)
        assertFalse(pending.succeeded)
        assertEquals(ResultKind.PENDING, pending.resultKind)
        assertEquals(4, pending.responseSequence)
        assertEquals(1, pending.originSequence)
        assertEquals(HelloCommand.NUMBER, pending.command)
        assertEquals(connection, pending.connection)
        assertTrue(tracker.isOpen(connection, HelloCommand.NUMBER, 1))

        val secondPending = match(
            tracker.accept(
                connection,
                response(responseSequence = 5, origin = 1, result = CommandResult.PENDING, body = byteArrayOf(0x01)),
            ),
        )
        assertTrue(secondPending.pending)
        assertTrue(tracker.isOpen(connection, HelloCommand.NUMBER, 1))

        val success = match(
            tracker.accept(connection, response(responseSequence = 6, origin = 1, result = CommandResult.SUCCESS)),
        )
        assertFalse(success.pending)
        assertTrue(success.succeeded)
        assertEquals(ResultKind.SUCCESS, success.resultKind)
        assertFalse(tracker.isOpen(connection, HelloCommand.NUMBER, 1))
        assertEquals(0, tracker.openCount)

        val late = unmatched(
            tracker.accept(connection, response(responseSequence = 7, origin = 1, result = CommandResult.SUCCESS)),
        )
        assertEquals(UnmatchedReason.NO_OPEN_REQUEST, late.reason)
    }

    @Test
    fun terminalResultsCloseAndUnknownIsNotSuccess() {
        assertTerminal(CommandResult.FAILURE, ResultKind.FAILURE)
        assertTerminal(CommandResult.UNSUPPORTED, ResultKind.UNSUPPORTED)
        assertTerminal(9, ResultKind.UNKNOWN)
    }

    @Test
    fun matchUsesCommandOriginAndConnection() {
        val tracker = RequestTracker()
        tracker.open(connection, HelloCommand.NUMBER, sequence = 1)
        tracker.open(connection, command = 26, sequence = 1)
        tracker.open(other, HelloCommand.NUMBER, sequence = 1)

        val wrongCommand = unmatched(
            tracker.accept(connection, response(command = 34, origin = 1, result = CommandResult.SUCCESS)),
        )
        assertEquals(UnmatchedReason.NO_OPEN_REQUEST, wrongCommand.reason)
        assertTrue(tracker.isOpen(connection, HelloCommand.NUMBER, 1))

        val wrongOrigin = unmatched(
            tracker.accept(connection, response(responseSequence = 1, origin = 2, result = CommandResult.SUCCESS)),
        )
        assertEquals(UnmatchedReason.NO_OPEN_REQUEST, wrongOrigin.reason)
        assertTrue(tracker.isOpen(connection, HelloCommand.NUMBER, 1))

        val wrongConnection = unmatched(
            tracker.accept(ConnectionId(9), response(origin = 1, result = CommandResult.SUCCESS)),
        )
        assertEquals(UnmatchedReason.NO_OPEN_REQUEST, wrongConnection.reason)

        val battery = match(
            tracker.accept(connection, response(command = 26, origin = 1, result = CommandResult.SUCCESS)),
        )
        assertEquals(26, battery.command)
        assertFalse(tracker.isOpen(connection, 26, 1))
        assertTrue(tracker.isOpen(connection, HelloCommand.NUMBER, 1))
        assertTrue(tracker.isOpen(other, HelloCommand.NUMBER, 1))
    }

    @Test
    fun responseSequenceIsNotTheRequestEcho() {
        val frame = response(responseSequence = 9, origin = 1, result = CommandResult.SUCCESS, body = byteArrayOf(0x0A))
        assertEquals(9, frame[9].toInt() and 0xFF)
        assertEquals(HelloCommand.NUMBER, frame[10].toInt() and 0xFF)
        assertEquals(1, frame[11].toInt() and 0xFF)
        assertEquals(CommandResult.SUCCESS, frame[12].toInt() and 0xFF)
        assertEquals(0x0A, frame[13].toInt() and 0xFF)
        assertEquals(HeaderWord.COMMON_RESPONSE, readU16Le(frame, 4))
        assertEquals(0x01, frame[4].toInt() and 0xFF)
        assertEquals(0x00, frame[5].toInt() and 0xFF)

        val tracker = RequestTracker()
        tracker.open(connection, HelloCommand.NUMBER, sequence = 1)
        val matched = match(tracker.accept(connection, frame))
        assertEquals(9, matched.responseSequence)
        assertEquals(1, matched.originSequence)
    }

    @Test
    fun sequenceByteWrapsAsTwoDistinctValues() {
        val tracker = RequestTracker()
        tracker.open(connection, HelloCommand.NUMBER, sequence = 255)
        tracker.open(connection, HelloCommand.NUMBER, sequence = 0)
        val high = match(
            tracker.accept(connection, response(origin = 255, result = CommandResult.PENDING)),
        )
        assertEquals(255, high.originSequence)
        assertTrue(high.pending)
        assertTrue(tracker.isOpen(connection, HelloCommand.NUMBER, 255))
        assertTrue(tracker.isOpen(connection, HelloCommand.NUMBER, 0))
        match(tracker.accept(connection, response(origin = 0, result = CommandResult.SUCCESS)))
        assertFalse(tracker.isOpen(connection, HelloCommand.NUMBER, 0))
        assertTrue(tracker.isOpen(connection, HelloCommand.NUMBER, 255))
    }

    @Test
    fun dropConnectionForgetsThatSessionOnly() {
        val tracker = RequestTracker()
        tracker.open(connection, HelloCommand.NUMBER, sequence = 1)
        tracker.open(connection, HelloCommand.NUMBER, sequence = 2)
        tracker.open(other, HelloCommand.NUMBER, sequence = 1)
        tracker.dropConnection(connection)
        assertEquals(1, tracker.openCount)
        assertFalse(tracker.isOpen(connection, HelloCommand.NUMBER, 1))
        val late = unmatched(
            tracker.accept(connection, response(origin = 1, result = CommandResult.SUCCESS)),
        )
        assertEquals(UnmatchedReason.NO_OPEN_REQUEST, late.reason)
        val kept = match(
            tracker.accept(other, response(origin = 1, result = CommandResult.SUCCESS)),
        )
        assertEquals(other, kept.connection)
    }

    @Test
    fun commandFrameIsNotAResponseAndLeavesTheRequestOpen() {
        val tracker = RequestTracker()
        tracker.open(connection, HelloCommand.NUMBER, sequence = 1)
        val unmatched = unmatched(tracker.accept(connection, HelloCommand.bytes()))
        assertEquals(UnmatchedReason.NOT_A_RESPONSE, unmatched.reason)
        assertTrue(tracker.isOpen(connection, HelloCommand.NUMBER, 1))
    }

    @Test
    fun type36WithoutOriginAndResultIsNotAResponse() {
        val tooSmall = FrameCodec.encode(
            headerWord = HeaderWord.COMMON_RESPONSE,
            packetType = PacketType.COMMAND_RESPONSE,
            sequence = 1,
            command = HelloCommand.NUMBER,
            body = byteArrayOf(),
        )
        assertEquals(null, FrameCodec.decode(tooSmall).ok().toCommandResponse())
        val tracker = RequestTracker()
        tracker.open(connection, HelloCommand.NUMBER, sequence = 1)
        val unmatched = unmatched(tracker.accept(connection, tooSmall))
        assertEquals(UnmatchedReason.NOT_A_RESPONSE, unmatched.reason)
        assertTrue(tracker.isOpen(connection, HelloCommand.NUMBER, 1))
    }

    @Test
    fun reassembledPendingReplyStillMatches() {
        val tracker = RequestTracker()
        tracker.open(connection, HelloCommand.NUMBER, sequence = 1)
        val bytes = response(responseSequence = 3, origin = 1, result = CommandResult.PENDING)
        val accumulator = FrameAccumulator()
        val frames = ArrayList<WireFrame>()
        for (byte in bytes) {
            frames += accumulator.append(byteArrayOf(byte)).frames()
        }
        val matched = match(tracker.onFrame(connection, frames.single()))
        assertTrue(matched.pending)
        assertTrue(tracker.isOpen(connection, HelloCommand.NUMBER, 1))
    }

    @Test
    fun emptyCommandBodyStillExposesResultBesidePadding() {
        val frame = FrameCodec.decode(
            response(responseSequence = 1, origin = 8, result = CommandResult.UNSUPPORTED),
        ).ok()
        val parsed = frame.toCommandResponse()
        checkNotNull(parsed)
        assertEquals(8, parsed.originSequence)
        assertEquals(CommandResult.UNSUPPORTED, parsed.result)
        assertEquals(ResultKind.UNSUPPORTED, parsed.resultKind)
        assertFalse(parsed.keepsRequestOpen)
        assertTrue(parsed.body.isNotEmpty())
        assertTrue(parsed.body.all { it == 0.toByte() })
    }

    @Test
    fun openRejectsOutOfRangeIds() {
        val tracker = RequestTracker()
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            tracker.open(connection, command = 256, sequence = 1)
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            tracker.open(connection, command = 1, sequence = -1)
        }
    }

    private fun assertTerminal(result: Int, kind: ResultKind) {
        val tracker = RequestTracker()
        tracker.open(connection, HelloCommand.NUMBER, sequence = 1)
        val matched = match(tracker.accept(connection, response(origin = 1, result = result)))
        assertFalse(matched.pending)
        assertFalse(matched.succeeded)
        assertEquals(kind, matched.resultKind)
        assertEquals(result, matched.result)
        assertFalse(tracker.isOpen(connection, HelloCommand.NUMBER, 1))
    }

    private fun RequestTracker.accept(connection: ConnectionId, bytes: ByteArray): ReplyMatch =
        onFrame(connection, FrameCodec.decode(bytes).ok())

    private fun response(
        responseSequence: Int = 2,
        command: Int = HelloCommand.NUMBER,
        origin: Int,
        result: Int,
        body: ByteArray = ByteArray(0),
    ): ByteArray = FrameCodec.encodeCommandResponse(
        sequence = responseSequence,
        command = command,
        originSequence = origin,
        result = result,
        body = body,
    )

    private fun match(reply: ReplyMatch): ReplyMatch.Matched =
        org.junit.jupiter.api.Assertions.assertInstanceOf(ReplyMatch.Matched::class.java, reply)

    private fun unmatched(reply: ReplyMatch): ReplyMatch.Unmatched =
        org.junit.jupiter.api.Assertions.assertInstanceOf(ReplyMatch.Unmatched::class.java, reply)
}
