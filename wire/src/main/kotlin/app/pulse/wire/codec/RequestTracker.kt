package app.pulse.wire.codec

/**
 * Identity of one link. A reply is matched only against requests opened on the
 * same connection. After reconnect, use a new id so a late notification cannot
 * complete the previous session's request.
 */
@JvmInline
value class ConnectionId(val value: Long)

object CommandResult {
    const val FAILURE: Int = 0
    const val SUCCESS: Int = 1
    const val PENDING: Int = 2
    const val UNSUPPORTED: Int = 3
}

enum class ResultKind {
    FAILURE,
    SUCCESS,
    PENDING,
    UNSUPPORTED,
    UNKNOWN,
    ;

    companion object {
        fun from(result: Int): ResultKind = when (result) {
            CommandResult.FAILURE -> FAILURE
            CommandResult.SUCCESS -> SUCCESS
            CommandResult.PENDING -> PENDING
            CommandResult.UNSUPPORTED -> UNSUPPORTED
            else -> UNKNOWN
        }
    }
}

/**
 * A packet-type 36 frame with the documented response fields.
 *
 * [body] starts at frame offset 13 and still includes outer zero padding.
 * The numeric [result] is preserved when it is outside 0..3.
 */
class CommandResponse(
    val frame: WireFrame,
    val responseSequence: Int,
    val command: Int,
    val originSequence: Int,
    val result: Int,
    val body: ByteArray,
) {
    val resultKind: ResultKind
        get() = ResultKind.from(result)

    val keepsRequestOpen: Boolean
        get() = result == CommandResult.PENDING
}

fun WireFrame.toCommandResponse(): CommandResponse? {
    if (packetType != PacketType.COMMAND_RESPONSE || tail.size < 2) {
        return null
    }
    return CommandResponse(
        frame = this,
        responseSequence = sequence,
        command = command,
        originSequence = tail[0].toInt() and 0xFF,
        result = tail[1].toInt() and 0xFF,
        body = tail.copyOfRange(2, tail.size),
    )
}

sealed class ReplyMatch {
    class Matched(
        val connection: ConnectionId,
        val command: Int,
        val originSequence: Int,
        val responseSequence: Int,
        val result: Int,
    ) : ReplyMatch() {
        val pending: Boolean
            get() = result == CommandResult.PENDING

        val resultKind: ResultKind
            get() = ResultKind.from(result)

        val succeeded: Boolean
            get() = result == CommandResult.SUCCESS
    }

    class Unmatched(
        val reason: UnmatchedReason,
        val frame: WireFrame,
    ) : ReplyMatch()
}

enum class UnmatchedReason {
    NOT_A_RESPONSE,
    NO_OPEN_REQUEST,
}

/**
 * Correlates command responses with the request that produced them.
 *
 * A reply matches on command, originating sequence, and connection. The
 * sequence is the 8-bit value from the request. The response frame's own
 * sequence is not the key. Result [CommandResult.PENDING] leaves the request
 * open for a later result. Any other result, including an unknown numeric
 * value, closes it. Unknown results are not successes.
 *
 * Not thread-safe. One tracker serves one caller that serializes events.
 */
class RequestTracker {
    private class Key(
        val connection: Long,
        val command: Int,
        val sequence: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is Key) {
                return false
            }
            return connection == other.connection &&
                command == other.command &&
                sequence == other.sequence
        }

        override fun hashCode(): Int {
            var result = connection.hashCode()
            result = 31 * result + command
            result = 31 * result + sequence
            return result
        }
    }

    private val open = HashSet<Key>()

    val openCount: Int
        get() = open.size

    fun open(connection: ConnectionId, command: Int, sequence: Int): Boolean {
        require(command in 0..0xFF) { "command out of range: $command" }
        require(sequence in 0..0xFF) { "sequence out of range: $sequence" }
        return open.add(Key(connection.value, command, sequence))
    }

    fun isOpen(connection: ConnectionId, command: Int, sequence: Int): Boolean =
        open.contains(Key(connection.value, command, sequence))

    fun dropConnection(connection: ConnectionId) {
        open.removeIf { it.connection == connection.value }
    }

    fun onFrame(connection: ConnectionId, frame: WireFrame): ReplyMatch {
        val response = frame.toCommandResponse()
            ?: return ReplyMatch.Unmatched(UnmatchedReason.NOT_A_RESPONSE, frame)
        val key = Key(connection.value, response.command, response.originSequence)
        if (!open.contains(key)) {
            return ReplyMatch.Unmatched(UnmatchedReason.NO_OPEN_REQUEST, frame)
        }
        if (!response.keepsRequestOpen) {
            open.remove(key)
        }
        return ReplyMatch.Matched(
            connection = connection,
            command = response.command,
            originSequence = response.originSequence,
            responseSequence = response.responseSequence,
            result = response.result,
        )
    }
}
