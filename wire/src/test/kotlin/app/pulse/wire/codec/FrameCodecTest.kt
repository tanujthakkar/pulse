package app.pulse.wire.codec

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrameCodecTest {
    @Test
    fun headerWordIsNotForcedToTheCommonRequestBytes() {
        val raw = FrameCodec.encode(
            headerWord = HeaderWord.COMMON_RESPONSE,
            packetType = 99,
            sequence = 0,
            command = 255,
            body = byteArrayOf(0x7E),
        )
        val frame = FrameCodec.decode(raw).ok()
        assertEquals(HeaderWord.COMMON_RESPONSE, frame.headerWord)
        assertEquals(0x01, raw[4].toInt() and 0xFF)
        assertEquals(0x00, raw[5].toInt() and 0xFF)
        assertEquals(99, frame.packetType)
        assertEquals(0, frame.sequence)
        assertEquals(255, frame.command)
        assertArrayEquals(byteArrayOf(0x7E), frame.tail)
        assertEquals(null, frame.toCommandResponse())
    }

    @Test
    fun arbitraryHeaderWordRoundTrips() {
        val raw = FrameCodec.encode(
            headerWord = 0xBEEF,
            packetType = PacketType.COMMAND,
            sequence = 6,
            command = 26,
            body = byteArrayOf(0x00),
        )
        assertEquals(0xBEEF, FrameCodec.decode(raw).ok().headerWord)
    }

    @Test
    fun explicitShortZeroBodyCanSharePaddingButALongerZeroBodyDoesNot() {
        val empty = encode(body = byteArrayOf())
        val oneZero = encode(body = byteArrayOf(0x00))
        val twoZeros = encode(body = byteArrayOf(0x00, 0x00))
        assertArrayEquals(empty, oneZero)
        assertFalse(empty.contentEquals(twoZeros))
        val frame = FrameCodec.decode(twoZeros).ok()
        assertEquals(0, frame.tail[0].toInt())
        assertEquals(0, frame.tail[1].toInt())
        assertTrue(frame.tail.size > 2)
    }

    @Test
    fun format2IsNotParsedWithFormat1Offsets() {
        val bytes = HelloCommand.bytes()
        bytes[1] = 0x40
        assertEquals(RejectReason.BAD_FORMAT, FrameCodec.decode(bytes).rejected())
    }

    @Test
    fun structuralRejections() {
        assertEquals(RejectReason.TOO_SHORT, FrameCodec.decode(ByteArray(15)).rejected())
        assertEquals(
            RejectReason.TOO_SHORT,
            FrameCodec.decode(HelloCommand.bytes(), offset = 0, length = 15).rejected(),
        )
        val badStart = HelloCommand.bytes()
        badStart[0] = 0
        assertEquals(RejectReason.BAD_START, FrameCodec.decode(badStart).rejected())

        val hello = HelloCommand.bytes()
        val extra = hello + byteArrayOf(0)
        assertEquals(RejectReason.LENGTH_MISMATCH, FrameCodec.decode(extra).rejected())

        val misaligned = ByteArray(17)
        misaligned[0] = FrameBytes.START
        misaligned[1] = FrameBytes.FORMAT
        writeU16Le(misaligned, 2, 9)
        assertEquals(RejectReason.LENGTH_ALIGNMENT, FrameCodec.decode(misaligned).rejected())

        val oversized = ByteArray(WireLimits.MAX_FRAME_BYTES + 1)
        oversized[0] = FrameBytes.START
        oversized[1] = FrameBytes.FORMAT
        assertEquals(RejectReason.FRAME_TOO_LARGE, FrameCodec.decode(oversized).rejected())
    }

    @Test
    fun decodeReadsAFrameAtAnOffset() {
        val hello = HelloCommand.bytes()
        val carrier = ByteArray(3) + hello + byteArrayOf(0x5A)
        val frame = FrameCodec.decode(carrier, offset = 3, length = hello.size).ok()
        assertArrayEquals(hello, frame.bytes)
    }

    @Test
    fun decodeRejectsABadSlice() {
        val hello = HelloCommand.bytes()
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.decode(hello, offset = -1, length = 16)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.decode(hello, offset = 0, length = hello.size + 1)
        }
    }

    @Test
    fun encodeRejectsOutOfRangeFieldsAndOversizedBodies() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.encode(headerWord = -1, packetType = 35, sequence = 1, command = 1, body = byteArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.encode(headerWord = 0, packetType = 256, sequence = 1, command = 1, body = byteArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.encode(headerWord = 0, packetType = 35, sequence = 256, command = 1, body = byteArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.encode(headerWord = 0, packetType = 35, sequence = 1, command = -1, body = byteArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.encode(
                headerWord = 0,
                packetType = 35,
                sequence = 1,
                command = 1,
                body = ByteArray(2126),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.encodeCommandResponse(
                sequence = 1,
                command = 1,
                originSequence = 256,
                result = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.encodeCommandResponse(
                sequence = 1,
                command = 1,
                originSequence = 1,
                result = -1,
            )
        }
    }

    @Test
    fun declaredLengthMatchesThePaddedSpan() {
        val body = byteArrayOf(0x01, 0x02)
        val raw = encode(body = body)
        val declared = readU16Le(raw, 2)
        assertEquals(raw.size, declared + 8)
        assertEquals(0, (declared - 4) % 4)
        assertTrue(raw.size > 15)
        val frame = FrameCodec.decode(raw).ok()
        assertEquals(0x01, frame.tail[0].toInt() and 0xFF)
        assertEquals(0x02, frame.tail[1].toInt() and 0xFF)
        assertEquals(0, frame.tail.drop(2).count { it.toInt() != 0 })
    }

    private fun encode(body: ByteArray): ByteArray =
        FrameCodec.encode(
            headerWord = HeaderWord.COMMON_REQUEST,
            packetType = PacketType.COMMAND,
            sequence = 1,
            command = 22,
            body = body,
        )
}
