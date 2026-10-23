package app.pulse.wire.codec

/**
 * Bounds and header bytes from spec §4 and §8.
 *
 * History delivery emits framed records only up to [MAX_FRAME_BYTES]. The
 * common header words are observed byte patterns, not required constants.
 */
object WireLimits {
    const val MAX_FRAME_BYTES: Int = 2140
    const val MIN_FRAME_BYTES: Int = 16
}

object HeaderWord {
    /** Bytes `00 01`. Common on requests. */
    const val COMMON_REQUEST: Int = 0x0100

    /** Bytes `01 00`. Common on responses. */
    const val COMMON_RESPONSE: Int = 0x0001
}

object PacketType {
    const val COMMAND: Int = 35
    const val COMMAND_RESPONSE: Int = 36
}

internal object FrameBytes {
    val START: Byte = 0xAA.toByte()
    const val FORMAT: Byte = 0x01
}

/**
 * One accepted format-1 frame.
 *
 * [tail] is every byte after the command marker (frame offset 10) up to, but
 * not including, the CRC-32 trailer. That slice is the command body followed
 * by its zero pad. Padding is not stripped: an explicit body `00` and a missing
 * body are different inputs to [FrameCodec.encode], and a trailing zero in the
 * body is not discarded on the way back out.
 *
 * [bytes] is the complete frame. Callers must not mutate [tail] or [bytes].
 */
class WireFrame(
    val headerWord: Int,
    val packetType: Int,
    val sequence: Int,
    val command: Int,
    val tail: ByteArray,
    val bytes: ByteArray,
)

enum class RejectReason {
    TOO_SHORT,
    BAD_START,
    BAD_FORMAT,
    FRAME_TOO_LARGE,
    LENGTH_MISMATCH,
    LENGTH_ALIGNMENT,
    BAD_HEADER_CRC,
    BAD_BODY_CRC,
}

sealed class DecodeResult {
    class Ok(val frame: WireFrame) : DecodeResult()

    class Rejected(val reason: RejectReason) : DecodeResult()
}

/**
 * Format-1 encode and decode.
 *
 * A bad format, size, or CRC is a [DecodeResult.Rejected] local failure. It is
 * not a command-result code. The slice passed to [decode] is one complete frame;
 * split notifications belong in [FrameAccumulator].
 */
object FrameCodec {
    /**
     * Encode one frame.
     *
     * [body] is copied as supplied and is not trimmed. Zero padding is appended
     * until the span from packet type through the pad is a multiple of 4.
     * Declared length `L` is that padded size plus 4. The complete frame is
     * `L + 8` bytes.
     *
     * At some lengths an explicit trailing `00` occupies the same place padding
     * would have filled for a shorter body, so the wire images match. Callers
     * still pass the body the spec names for that command.
     */
    fun encode(
        headerWord: Int,
        packetType: Int,
        sequence: Int,
        command: Int,
        body: ByteArray,
    ): ByteArray {
        require(headerWord in 0..0xFFFF) { "header word out of range: $headerWord" }
        require(packetType in 0..0xFF) { "packet type out of range: $packetType" }
        require(sequence in 0..0xFF) { "sequence out of range: $sequence" }
        require(command in 0..0xFF) { "command out of range: $command" }
        val unpadded = 3 + body.size
        val pad = (4 - (unpadded % 4)) % 4
        val padded = unpadded + pad
        val frameLength = padded + 12
        require(frameLength >= WireLimits.MIN_FRAME_BYTES)
        require(frameLength <= WireLimits.MAX_FRAME_BYTES) {
            "frame is $frameLength bytes; limit is ${WireLimits.MAX_FRAME_BYTES}"
        }
        val out = ByteArray(frameLength)
        out[0] = FrameBytes.START
        out[1] = FrameBytes.FORMAT
        writeU16Le(out, 2, padded + 4)
        writeU16Le(out, 4, headerWord)
        writeU16Le(out, 6, Crc16Modbus.compute(out, 0, 6))
        out[8] = packetType.toByte()
        out[9] = sequence.toByte()
        out[10] = command.toByte()
        body.copyInto(out, destinationOffset = 11)
        writeU32Le(out, 8 + padded, Crc32IsoHdlc.compute(out, 8, padded))
        return out
    }

    /**
     * Encode a packet-type 36 response.
     *
     * The response frame has its own [sequence]. [originSequence] is the request
     * being answered and is stored at frame offset 11, after the command byte.
     */
    fun encodeCommandResponse(
        sequence: Int,
        command: Int,
        originSequence: Int,
        result: Int,
        body: ByteArray = ByteArray(0),
        headerWord: Int = HeaderWord.COMMON_RESPONSE,
    ): ByteArray {
        require(originSequence in 0..0xFF) { "origin sequence out of range: $originSequence" }
        require(result in 0..0xFF) { "result out of range: $result" }
        val payload = ByteArray(2 + body.size)
        payload[0] = originSequence.toByte()
        payload[1] = result.toByte()
        body.copyInto(payload, destinationOffset = 2)
        return encode(
            headerWord = headerWord,
            packetType = PacketType.COMMAND_RESPONSE,
            sequence = sequence,
            command = command,
            body = payload,
        )
    }

    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): DecodeResult {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        if (length < WireLimits.MIN_FRAME_BYTES) {
            return DecodeResult.Rejected(RejectReason.TOO_SHORT)
        }
        if (bytes[offset] != FrameBytes.START) {
            return DecodeResult.Rejected(RejectReason.BAD_START)
        }
        if (bytes[offset + 1] != FrameBytes.FORMAT) {
            return DecodeResult.Rejected(RejectReason.BAD_FORMAT)
        }
        if (length > WireLimits.MAX_FRAME_BYTES) {
            return DecodeResult.Rejected(RejectReason.FRAME_TOO_LARGE)
        }
        val declared = readU16Le(bytes, offset + 2)
        if (declared + 8 != length) {
            return DecodeResult.Rejected(RejectReason.LENGTH_MISMATCH)
        }
        if (declared < 4 || (declared - 4) % 4 != 0) {
            return DecodeResult.Rejected(RejectReason.LENGTH_ALIGNMENT)
        }
        val storedHeader = readU16Le(bytes, offset + 6)
        val actualHeader = Crc16Modbus.compute(bytes, offset, 6)
        if (storedHeader != actualHeader) {
            return DecodeResult.Rejected(RejectReason.BAD_HEADER_CRC)
        }
        val storedBody = readU32Le(bytes, offset + length - 4)
        val actualBody = Crc32IsoHdlc.compute(bytes, offset + 8, length - 12)
        if (storedBody != actualBody) {
            return DecodeResult.Rejected(RejectReason.BAD_BODY_CRC)
        }
        val frameBytes = bytes.copyOfRange(offset, offset + length)
        val tail = frameBytes.copyOfRange(11, frameBytes.size - 4)
        return DecodeResult.Ok(
            WireFrame(
                headerWord = readU16Le(frameBytes, 4),
                packetType = frameBytes[8].toInt() and 0xFF,
                sequence = frameBytes[9].toInt() and 0xFF,
                command = frameBytes[10].toInt() and 0xFF,
                tail = tail,
                bytes = frameBytes,
            ),
        )
    }
}
