package app.pulse.wire.codec

/**
 * Commands Pulse does not send.
 *
 * [NUMBERS] is the deny-list in protocol spec §12. There is no override.
 * [CERTIFICATE] is the certificate and authorization family in that same
 * section: Pulse does not send those either. Command 23 is not on the
 * deny-list; [HistoryAckGate] keeps it off until metadata offsets are confirmed.
 */
object DeniedCommands {
    val NUMBERS: Set<Int> = setOf(
        25,
        29,
        32,
        33,
        36,
        37,
        38,
        45,
        99,
        107,
        119,
        120,
        142,
        143,
        144,
        153,
        154,
    )

    /** Spec §12. Certificate and authorization commands. Not sent. */
    val CERTIFICATE: IntRange = 155..159

    fun allows(command: Int): Boolean {
        if (command !in 0..0xFF) return false
        if (command in NUMBERS) return false
        if (command in CERTIFICATE) return false
        if (command == HistoryAckGate.NUMBER) return HistoryAckGate.maySend()
        return true
    }
}

/**
 * History acknowledgement (command 23).
 *
 * Spec §8 and project rule 13: live acknowledgements stay off until a Pulse
 * capture confirms the metadata kind byte and the 8-byte token. [maySend]
 * stays false until that confirmation is compiled in. [debugRequested] does
 * not turn sending on by itself.
 */
object HistoryAckGate {
    const val NUMBER: Int = 23

    /**
     * Debug request. A debug build may set this. Sending still requires
     * [OFFSETS_CONFIRMED], which stays false through this milestone.
     */
    @Volatile
    var debugRequested: Boolean = false

    const val OFFSETS_CONFIRMED: Boolean = false

    fun maySend(): Boolean {
        val requested = debugRequested
        val confirmed = OFFSETS_CONFIRMED
        return requested && confirmed
    }
}

/**
 * Encode one command request after the deny-list and the history-ack gate.
 *
 * Command writes are format-1 frames. The caller supplies the body the spec
 * names for that command. An empty body is not rewritten into an explicit `00`.
 */
object CommandFrames {
    fun request(command: Int, sequence: Int, body: ByteArray): ByteArray {
        require(DeniedCommands.allows(command)) { "command $command is not sent" }
        return FrameCodec.encode(
            headerWord = HeaderWord.COMMON_REQUEST,
            packetType = PacketType.COMMAND,
            sequence = sequence,
            command = command,
            body = body,
        )
    }
}
