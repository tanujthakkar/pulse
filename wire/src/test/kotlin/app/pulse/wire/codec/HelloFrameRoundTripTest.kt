package app.pulse.wire.codec

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class HelloFrameRoundTripTest {
    @Test
    fun documentedHelloMatchesTheFixedSpecBytes() {
        val bytes = HelloCommand.bytes()
        assertEquals("AA0108000001E67123019101363E5C8D", bytes.toHex())
        assertEquals(16, bytes.size)
        assertEquals(8, readU16Le(bytes, 2))
        assertEquals(0x71E6, readU16Le(bytes, 6))
        assertEquals(0x8D5C3E36L, readU32Le(bytes, 12))
    }

    @Test
    fun documentedHelloDecodesAndReencodes() {
        val bytes = HelloCommand.bytes()
        val frame = FrameCodec.decode(bytes).ok()
        assertEquals(HeaderWord.COMMON_REQUEST, frame.headerWord)
        assertEquals(PacketType.COMMAND, frame.packetType)
        assertEquals(HelloCommand.DOCUMENTED_SEQUENCE, frame.sequence)
        assertEquals(HelloCommand.NUMBER, frame.command)
        assertArrayEquals(byteArrayOf(HelloCommand.REVISION.toByte()), frame.tail)
        assertArrayEquals(bytes, frame.bytes)
        val again = FrameCodec.encode(
            headerWord = frame.headerWord,
            packetType = frame.packetType,
            sequence = frame.sequence,
            command = frame.command,
            body = frame.tail.copyOf(),
        )
        assertArrayEquals(bytes, again)
    }

    @Test
    fun otherHelloSequencesUseTheSameRevisionAndStillRoundTrip() {
        val bytes = HelloCommand.bytes(sequence = 2)
        assertNotEquals(HelloCommand.bytes().toHex(), bytes.toHex())
        val frame = FrameCodec.decode(bytes).ok()
        assertEquals(2, frame.sequence)
        assertEquals(HelloCommand.NUMBER, frame.command)
        assertArrayEquals(byteArrayOf(0x01), frame.tail)
        assertArrayEquals(
            bytes,
            FrameCodec.encode(
                headerWord = frame.headerWord,
                packetType = frame.packetType,
                sequence = frame.sequence,
                command = frame.command,
                body = frame.tail.copyOf(),
            ),
        )
    }
}
