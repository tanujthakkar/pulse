package app.pulse.testing.fakestrap

import app.pulse.wire.codec.FrameCodec
import app.pulse.wire.codec.HistoryMetadata
import app.pulse.wire.codec.HistoryRecord
import app.pulse.wire.codec.HistoryRecordDecoder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class FakeStrapTest {
    @Test
    fun placeholdersConstruct() {
        assertEquals("0", FakeStrap().specVersion)
        assertNotNull(FakeGattSession())
    }

    @Test
    fun sampleRecordDecodesAndMetadataTokenSitsAtTheCaptureOffsets() {
        val record = sampleRecord()
        val decoded = assertInstanceOf(HistoryRecord.Layout18::class.java, HistoryRecordDecoder.decode(record))
        assertEquals(72, decoded.reading.heartRateBpm)
        assertEquals(41L, decoded.reading.recordIndex)
        val token = sampleToken()
        val end = FrameCodec.encode(
            headerWord = app.pulse.wire.codec.HeaderWord.COMMON_RESPONSE,
            packetType = app.pulse.wire.codec.PacketType.HISTORY_METADATA,
            sequence = 1,
            command = HistoryMetadata.END,
            body = ByteArray(18).also { token.copyInto(it, destinationOffset = 10) },
        )
        val frame = FrameCodec.decode(end)
        val ok = assertInstanceOf(app.pulse.wire.codec.DecodeResult.Ok::class.java, frame).frame
        assertArrayEquals(token, HistoryMetadata.read(ok)?.token)
    }
}
