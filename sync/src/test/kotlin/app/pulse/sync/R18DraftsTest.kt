package app.pulse.sync

import app.pulse.wire.codec.ActivityKind
import app.pulse.wire.codec.BandMotion
import app.pulse.wire.codec.HistoryRecord
import app.pulse.wire.codec.HistoryRecordDecoder
import app.pulse.wire.codec.Layout18Reading
import app.pulse.wire.codec.Layout18Stored
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class R18DraftsTest {
    @Test
    fun layout18StampsTheDraftTheStoreAccepts() {
        val raw = byteArrayOf(0xAA.toByte(), 18, 72)
        val ticks = intArrayOf(1024, 0, 1)
        val block = byteArrayOf(0, 4, 0, 0, 1, 0, 0, 0)
        val axis = floatArrayOf(-0.5f, 0.25f, Float.NaN)
        val axisBits = intArrayOf(1, 2, 3)
        val aux = floatArrayOf(36.5f, Float.NaN)
        val auxRaw = intArrayOf(365, -10)
        val status = intArrayOf(0x1111, 0x2222, 0x3333)
        val stored = Layout18Stored(
            recordIndex = 3_000_000_001L,
            unixSeconds = 1_735_689_600L,
            heartRateRaw = 72,
            rrCount = 5,
            rrTicks = ticks,
            rrBlockRaw = block,
            cardiacRaw = 0xA5,
            qualityRaw = 0x3C,
            alternateHeartRateRaw = 99,
            packedWordRaw = 0xBEEF,
            dynamicAccel = 1.25f,
            dynamicAccelBits = 1.25f.toRawBits(),
            axisAccel = axis,
            axisAccelBits = axisBits,
            stepCounter = 4000,
            cadenceRaw = 0x01FF,
            hardwareCounter = 0x0ABC,
            activityClass = 2,
            activityClassRaw = 2,
            counterOverrideRaw = 0x10,
            auxTempC = aux,
            auxTempRaw = auxRaw,
            skinTempRaw = 3650,
            statusRaw = status,
            motionRaw = 0x6B,
            restState = 2,
            sleepAdjacentRaw = 0x5A,
            crc32 = 0x1234_5678L,
            raw = raw,
        )
        val reading = Layout18Reading(
            recordIndex = stored.recordIndex,
            unixSeconds = stored.unixSeconds,
            heartRateBpm = 72,
            rrMillis = listOf(1000, 1),
            stepCounter = 4000,
            activity = ActivityKind.RUN,
            skinTempC = 36.5f,
            bandState = BandMotion.SLEEP,
        )
        val decoded = HistoryRecord.Layout18(
            decoderVersion = HistoryRecordDecoder.VERSION,
            reading = reading,
            stored = stored,
        )
        val draft = decoded.toR18Draft()

        assertEquals(HistoryRecordDecoder.VERSION, decoded.decoderVersion)
        assertEquals(reading.recordIndex, draft.recordIndex)
        assertEquals(reading.unixSeconds, draft.unixSeconds)
        assertNull(draft.subsecondTicks)
        assertEquals(reading.heartRateBpm, draft.heartRateBpm)
        assertEquals(72, draft.heartRateRaw)
        assertEquals(5, draft.rrCount)
        assertArrayEquals(ticks, draft.rrTicks)
        assertArrayEquals(block, draft.rrBlockRaw)
        assertEquals(0xA5, draft.cardiacRaw)
        assertEquals(0x3C, draft.qualityRaw)
        assertEquals(99, draft.alternateHeartRateRaw)
        assertEquals(0xBEEF, draft.packedWordRaw)
        assertEquals(1.25f, draft.dynamicAccel)
        assertEquals(1.25f.toRawBits(), draft.dynamicAccelBits)
        assertArrayEquals(axis, draft.axisAccel, 0f)
        assertTrue(draft.axisAccel[2].isNaN())
        assertArrayEquals(axisBits, draft.axisAccelBits)
        assertEquals(4000, draft.stepCounter)
        assertEquals(0x01FF, draft.cadenceRaw)
        assertEquals(0x0ABC, draft.hardwareCounter)
        assertEquals(2, draft.activityClass)
        assertEquals(2, draft.activityClassRaw)
        assertEquals(0x10, draft.counterOverrideRaw)
        assertArrayEquals(floatArrayOf(36.5f), floatArrayOf(draft.auxTempC[0]), 0f)
        assertTrue(draft.auxTempC[1].isNaN())
        assertArrayEquals(auxRaw, draft.auxTempRaw)
        assertEquals(36.5f, draft.skinTempC)
        assertEquals(3650, draft.skinTempRaw)
        assertArrayEquals(status, draft.statusRaw)
        assertEquals(0x6B, draft.motionRaw)
        assertEquals(2, draft.restState)
        assertEquals(0x5A, draft.sleepAdjacentRaw)
        assertEquals(0x1234_5678L, draft.crc32)
        assertArrayEquals(raw, draft.raw)
        assertTrue(draft.rrTicks.size <= 4)
        assertEquals(3, draft.axisAccel.size)
        assertEquals(3, draft.axisAccelBits.size)
        assertEquals(2, draft.auxTempC.size)
        assertEquals(2, draft.auxTempRaw.size)
        assertEquals(3, draft.statusRaw.size)

        ticks[0] = 7
        raw[0] = 0
        assertEquals(1024, draft.rrTicks[0])
        assertEquals(0xAA.toByte(), draft.raw[0])
    }

    @Test
    fun unknownLayoutBecomesAnArchivedFrameWithoutASample() {
        val bytes = ByteArray(30) { 0xAB.toByte() }
        bytes[8] = 47
        bytes[9] = 20
        val unknown = HistoryRecord.Unknown(layout = 20, bytes = bytes)
        val draft = unknown.toRawFrameDraft()
        assertEquals(20, draft.layout)
        assertEquals(47, draft.packetType)
        assertArrayEquals(bytes, draft.bytes)
        assertNull(draft.recordIndex)
        assertNull(draft.unixSeconds)
        bytes[9] = 0
        assertEquals(20.toByte(), draft.bytes[9])
    }
}
