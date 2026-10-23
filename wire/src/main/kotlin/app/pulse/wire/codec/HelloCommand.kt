package app.pulse.wire.codec

/**
 * Client hello, command 145 revision 1.
 *
 * The documented frame is sequence 1 and header bytes `00 01`. Spec §13 fixes
 * those 16 bytes. [bytes] with the default sequence must reproduce them.
 */
object HelloCommand {
    const val NUMBER: Int = 145
    const val REVISION: Int = 1
    const val DOCUMENTED_SEQUENCE: Int = 1

    fun bytes(sequence: Int = DOCUMENTED_SEQUENCE): ByteArray =
        FrameCodec.encode(
            headerWord = HeaderWord.COMMON_REQUEST,
            packetType = PacketType.COMMAND,
            sequence = sequence,
            command = NUMBER,
            body = byteArrayOf(REVISION.toByte()),
        )
}
