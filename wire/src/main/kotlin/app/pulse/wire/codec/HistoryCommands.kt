package app.pulse.wire.codec

/**
 * Clock, data-range, and history commands for one sync attempt.
 *
 * Numbers and bodies are spec §7 and §8. The metadata kind byte and the
 * 8-byte end token use the capture offsets in §8. Those offsets are not
 * confirmed by a Pulse capture, so [HistoryAckGate] stays closed. This file
 * does not flip that gate and does not rebuild a token.
 */
object ClockCommand {
    const val GET: Int = 147
    const val SET: Int = 146
    const val REVISION: Int = 1
    const val BODY_BYTES: Int = 7

    /** Largest fraction the set command accepts. Spec §7. */
    const val MAX_TICKS: Int = 32_767

    fun getBody(): ByteArray = byteArrayOf(REVISION.toByte())

    fun setBody(unixSeconds: Long, ticks: Int): ByteArray {
        require(unixSeconds in 0..0xFFFF_FFFFL) { "clock seconds do not fit" }
        require(ticks in 0..MAX_TICKS) { "clock ticks must be 0..$MAX_TICKS" }
        val body = ByteArray(BODY_BYTES)
        body[0] = REVISION.toByte()
        writeU32Le(body, 1, unixSeconds)
        writeU16Le(body, 5, ticks)
        return body
    }

    /**
     * Reads a 7-byte clock body: revision, `u32` Unix seconds, `u16` ticks.
     *
     * Extra padding is ignored. A short body is absent. Zero seconds is still
     * returned; the caller decides whether that is a failed read.
     */
    fun parse(body: ByteArray): StrapClock? {
        if (body.size < BODY_BYTES) return null
        return StrapClock(
            revision = body[0].toInt() and 0xFF,
            unixSeconds = readU32Le(body, 1),
            ticks = readU16Le(body, 5),
        )
    }
}

class StrapClock(
    val revision: Int,
    val unixSeconds: Long,
    val ticks: Int,
)

object DataRangeCommand {
    const val NUMBER: Int = 34
    const val BODY_BYTES: Int = 65

    fun requestBody(): ByteArray = ByteArray(0)

    /** The first 65 body bytes, or null when the final body is short. */
    fun snapshot(body: ByteArray): ByteArray? {
        if (body.size < BODY_BYTES) return null
        return body.copyOf(BODY_BYTES)
    }
}

object HistoryStartCommand {
    const val NUMBER: Int = 22

    /** Explicit `00`. Not the same request as [EMPTY]. Spec §7 and project rule 7. */
    val EXPLICIT_ZERO: ByteArray = byteArrayOf(0)

    val EMPTY: ByteArray = ByteArray(0)

    /** States that fail command 22 even when the result byte is success. */
    val FAIL_STATES: Set<Int> = setOf(6, 7, 9, 10)

    fun state(body: ByteArray): Int? {
        if (body.isEmpty()) return null
        return body[0].toInt() and 0xFF
    }
}

/**
 * Command 23 body: `01` plus the original 8 token bytes.
 *
 * [body] copies [token]. It does not rebuild the token from a timestamp or
 * from a saved cursor. Sending still goes through [HistoryAckGate].
 */
object HistoryAckCommand {
    const val NUMBER: Int = HistoryAckGate.NUMBER
    const val PREFIX: Int = 0x01
    const val TOKEN_BYTES: Int = 8

    fun body(token: ByteArray): ByteArray {
        require(token.size == TOKEN_BYTES) { "history token is $TOKEN_BYTES bytes" }
        val out = ByteArray(1 + TOKEN_BYTES)
        out[0] = PREFIX.toByte()
        token.copyInto(out, destinationOffset = 1)
        return out
    }
}

/**
 * Type 49 start, end, and complete.
 *
 * Kind is frame byte 10. The end token is frame offsets 21 through 28
 * inclusive. Spec §8 marks those absolute offsets as observed, not confirmed
 * by a Pulse capture. A short frame has a kind and no token. Callers must not
 * invent the missing bytes.
 */
object HistoryMetadata {
    const val START: Int = 1
    const val END: Int = 2
    const val COMPLETE: Int = 3
    const val KIND_OFFSET: Int = 10
    const val TOKEN_OFFSET: Int = 21
    const val TOKEN_BYTES: Int = HistoryAckCommand.TOKEN_BYTES

    fun read(frame: WireFrame): MetadataView? {
        if (frame.packetType != PacketType.HISTORY_METADATA) return null
        if (frame.bytes.size <= KIND_OFFSET) return null
        val kind = frame.bytes[KIND_OFFSET].toInt() and 0xFF
        val token = if (frame.bytes.size >= TOKEN_OFFSET + TOKEN_BYTES) {
            frame.bytes.copyOfRange(TOKEN_OFFSET, TOKEN_OFFSET + TOKEN_BYTES)
        } else {
            null
        }
        return MetadataView(kind, token)
    }
}

class MetadataView(
    val kind: Int,
    val token: ByteArray?,
)
