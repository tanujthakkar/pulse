package app.pulse.wire.codec

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistoryCommandsTest {
    @Test
    fun clockGetAndSetBodiesMatchTheContract() {
        assertArrayEquals(byteArrayOf(1), ClockCommand.getBody())
        val set = ClockCommand.setBody(unixSeconds = 1_700_000_050L, ticks = 12_345)
        assertEquals(7, set.size)
        assertEquals(1, set[0].toInt() and 0xFF)
        assertEquals(1_700_000_050L, readU32Le(set, 1))
        assertEquals(12_345, readU16Le(set, 5))
        val parsed = ClockCommand.parse(set)
        assertEquals(1, parsed?.revision)
        assertEquals(1_700_000_050L, parsed?.unixSeconds)
        assertEquals(12_345, parsed?.ticks)
        assertNull(ClockCommand.parse(ByteArray(6)))
        val padded = set + byteArrayOf(0, 0)
        assertEquals(1_700_000_050L, ClockCommand.parse(padded)?.unixSeconds)
        assertThrows(IllegalArgumentException::class.java) {
            ClockCommand.setBody(1, ticks = 32_768)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClockCommand.setBody(-1, ticks = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClockCommand.setBody(0x1_0000_0000L, ticks = 0)
        }
    }

    @Test
    fun dataRangeSnapshotKeepsTheFirst65Bytes() {
        assertEquals(0, DataRangeCommand.requestBody().size)
        assertNull(DataRangeCommand.snapshot(ByteArray(64)))
        val body = ByteArray(70) { it.toByte() }
        val snapshot = DataRangeCommand.snapshot(body)
        assertEquals(65, snapshot?.size)
        assertEquals(1, snapshot?.get(1)?.toInt())
        assertEquals(64, snapshot?.get(64)?.toInt()?.and(0xFF))
        body[1] = 9
        assertEquals(1, snapshot?.get(1)?.toInt())
    }

    @Test
    fun historyStartKeepsExplicitZeroDistinctFromEmpty() {
        assertArrayEquals(byteArrayOf(0), HistoryStartCommand.EXPLICIT_ZERO)
        assertEquals(0, HistoryStartCommand.EMPTY.size)
        assertFalse(HistoryStartCommand.EXPLICIT_ZERO.contentEquals(HistoryStartCommand.EMPTY))
        assertEquals(setOf(6, 7, 9, 10), HistoryStartCommand.FAIL_STATES)
        assertEquals(4, HistoryStartCommand.state(byteArrayOf(4, 0, 0)))
        assertNull(HistoryStartCommand.state(ByteArray(0)))
    }

    @Test
    fun ackBodyPrefixesTheOriginalToken() {
        val token = byteArrayOf(0x10, 0x32, 0x54, 0x76, 0x98.toByte(), 0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte())
        val body = HistoryAckCommand.body(token)
        assertEquals(HistoryAckGate.NUMBER, HistoryAckCommand.NUMBER)
        assertEquals(9, body.size)
        assertEquals(0x01, body[0].toInt() and 0xFF)
        assertArrayEquals(token, body.copyOfRange(1, 9))
        token[0] = 0
        assertEquals(0x10, body[1].toInt() and 0xFF)
        assertThrows(IllegalArgumentException::class.java) {
            HistoryAckCommand.body(ByteArray(7))
        }
        assertFalse(HistoryAckGate.maySend())
    }

    @Test
    fun metadataTokenIsTheCaptureSliceAndIsAbsentWhenShort() {
        val token = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val body = ByteArray(18)
        token.copyInto(body, destinationOffset = 10)
        val frame = FrameCodec.decode(
            FrameCodec.encode(
                headerWord = HeaderWord.COMMON_RESPONSE,
                packetType = PacketType.HISTORY_METADATA,
                sequence = 4,
                command = HistoryMetadata.END,
                body = body,
            ),
        ).ok()
        val view = HistoryMetadata.read(frame)
        assertEquals(HistoryMetadata.END, view?.kind)
        assertEquals(10, HistoryMetadata.KIND_OFFSET)
        assertEquals(21, HistoryMetadata.TOKEN_OFFSET)
        assertArrayEquals(token, view?.token)
        assertArrayEquals(token, frame.bytes.copyOfRange(21, 29))

        val short = FrameCodec.decode(
            FrameCodec.encode(
                headerWord = HeaderWord.COMMON_RESPONSE,
                packetType = PacketType.HISTORY_METADATA,
                sequence = 1,
                command = HistoryMetadata.END,
                body = ByteArray(0),
            ),
        ).ok()
        assertEquals(HistoryMetadata.END, HistoryMetadata.read(short)?.kind)
        assertNull(HistoryMetadata.read(short)?.token)

        val other = FrameCodec.decode(
            FrameCodec.encode(
                headerWord = HeaderWord.COMMON_REQUEST,
                packetType = PacketType.HISTORY_RECORD,
                sequence = 18,
                command = 0,
                body = ByteArray(0),
            ),
        ).ok()
        assertNull(HistoryMetadata.read(other))
    }
}
