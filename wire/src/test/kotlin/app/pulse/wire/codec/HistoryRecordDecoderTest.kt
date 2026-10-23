package app.pulse.wire.codec

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Layout-18 frames built from spec §9. Checksums use the format-1 parameters
 * in §4. No captured strap bytes.
 */
class HistoryRecordDecoderTest {
    @Test
    fun goldenRecordDecodesEveryField() {
        val bytes = golden()
        val decoded = asLayout(bytes)

        assertEquals(HistoryRecordDecoder.VERSION, decoded.decoderVersion)
        assertEquals(INDEX, decoded.reading.recordIndex)
        assertEquals(UNIX, decoded.reading.unixSeconds)
        assertEquals(72, decoded.reading.heartRateBpm)
        assertEquals(listOf(1000, 1, 1054, 63999), decoded.reading.rrMillis)
        assertEquals(4000, decoded.reading.stepCounter)
        assertEquals(ActivityKind.RUN, decoded.reading.activity)
        assertEquals(36.5f, decoded.reading.skinTempC)
        assertEquals(BandMotion.SLEEP, decoded.reading.bandState)
        assertEquals(
            setOf(
                "recordIndex",
                "unixSeconds",
                "heartRateBpm",
                "rrMillis",
                "stepCounter",
                "activity",
                "skinTempC",
                "bandState",
            ),
            Layout18Reading::class.java.declaredFields.map { it.name }.toSet(),
        )

        val stored = decoded.stored
        assertEquals(INDEX, stored.recordIndex)
        assertEquals(UNIX, stored.unixSeconds)
        assertEquals(72, stored.heartRateRaw)
        assertEquals(4, stored.rrCount)
        assertArrayEquals(intArrayOf(1024, 1, 1079, 65535), stored.rrTicks)
        assertEquals(8, stored.rrBlockRaw.size)
        assertEquals(0xA5, stored.cardiacRaw)
        assertEquals(0x3C, stored.qualityRaw)
        assertEquals(99, stored.alternateHeartRateRaw)
        assertEquals(0xBEEF, stored.packedWordRaw)
        assertEquals(1.25f, stored.dynamicAccel)
        assertEquals(1.25f.toRawBits(), stored.dynamicAccelBits)
        assertArrayEquals(floatArrayOf(-0.5f, 0.25f, 2f), stored.axisAccel, 0f)
        assertArrayEquals(
            intArrayOf((-0.5f).toRawBits(), 0.25f.toRawBits(), 2f.toRawBits()),
            stored.axisAccelBits,
        )
        assertEquals(4000, stored.stepCounter)
        assertEquals(0x01FF, stored.cadenceRaw)
        assertEquals(0x0ABC, stored.hardwareCounter)
        assertEquals(2, stored.activityClass)
        assertEquals(2, stored.activityClassRaw)
        assertEquals(0x10, stored.counterOverrideRaw)
        assertArrayEquals(floatArrayOf(36.5f, 20f), stored.auxTempC, 0f)
        assertArrayEquals(intArrayOf(365, 200), stored.auxTempRaw)
        assertEquals(3650, stored.skinTempRaw)
        assertArrayEquals(intArrayOf(0x1111, 0x2222, 0x3333), stored.statusRaw)
        assertEquals(0x6B, stored.motionRaw)
        assertEquals(2, stored.restState)
        assertEquals(0x5A, stored.sleepAdjacentRaw)
        assertEquals(Crc32IsoHdlc.compute(bytes, 8, 112), stored.crc32)
        assertArrayEquals(bytes, stored.raw)
        assertEquals(0x7E, stored.raw[10].toInt() and 0xFF)
        assertEquals(0x11, stored.raw[32].toInt() and 0xFF)
        assertEquals(0x55, stored.raw[34].toInt() and 0xFF)
        assertEquals(0x22, stored.raw[40].toInt() and 0xFF)
        assertEquals(0x44, stored.raw[83].toInt() and 0xFF)
        assertEquals(47, stored.raw[8].toInt() and 0xFF)

        bytes[22] = 0
        assertEquals(72, decoded.reading.heartRateBpm)
        assertEquals(72, decoded.stored.raw[22].toInt() and 0xFF)
    }

    @Test
    fun rrTickRoundingUsesThePositiveTickFormula() {
        assertEquals(1, rrTicksToMillis(1))
        assertEquals(500, rrTicksToMillis(512))
        assertEquals(1000, rrTicksToMillis(1024))
        assertEquals(1054, rrTicksToMillis(1079))
        assertEquals(63999, rrTicksToMillis(65535))
        assertThrows(IllegalArgumentException::class.java) { rrTicksToMillis(0) }
        assertThrows(IllegalArgumentException::class.java) { rrTicksToMillis(-1) }

        val decoded = asLayout(
            frame {
                it[23] = 3
                writeU16Le(it, 24, 0)
                writeU16Le(it, 26, 1024)
                writeU16Le(it, 28, 1)
            },
        )
        assertArrayEquals(intArrayOf(0, 1024, 1), decoded.stored.rrTicks)
        assertEquals(listOf(1000, 1), decoded.reading.rrMillis)
    }

    @Test
    fun rrCountAboveFourKeepsFourWordsAndTheRawCount() {
        val decoded = asLayout(
            frame {
                it[23] = 5
                writeU16Le(it, 24, 1024)
                writeU16Le(it, 26, 512)
                writeU16Le(it, 28, 1024)
                writeU16Le(it, 30, 1024)
                it[32] = 0xFF.toByte()
                it[33] = 0xEE.toByte()
            },
        )
        assertEquals(5, decoded.stored.rrCount)
        assertEquals(4, decoded.stored.rrTicks.size)
        assertEquals(0xEE, decoded.stored.cardiacRaw)
        assertEquals(listOf(1000, 500, 1000, 1000), decoded.reading.rrMillis)
    }

    @Test
    fun heartRateOutsideTheDisplayWindowIsNull() {
        val cases = listOf(0 to null, 24 to null, 25 to 25, 72 to 72, 230 to 230, 231 to null, 255 to null)
        for ((raw, shown) in cases) {
            val decoded = asLayout(frame { it[22] = raw.toByte() })
            assertEquals(shown, decoded.reading.heartRateBpm, raw.toString())
            assertEquals(raw, decoded.stored.heartRateRaw)
        }
    }

    @Test
    fun skinTemperatureWindowAndUnavailableSentinel() {
        val cases = listOf(
            -5000 to null,
            499 to null,
            500 to 5f,
            3650 to 36.5f,
            4500 to 45f,
            4501 to null,
        )
        for ((raw, shown) in cases) {
            val decoded = asLayout(frame { writeU16Le(it, 73, raw) })
            assertEquals(shown, decoded.reading.skinTempC, raw.toString())
            assertEquals(raw and 0xFFFF, decoded.stored.skinTempRaw)
        }
        val sentinel = asLayout(frame { writeU16Le(it, 73, -5000) })
        assertEquals(-50f, (sentinel.stored.skinTempRaw.toShort().toInt() / 100f))
        assertNull(sentinel.reading.skinTempC)
    }

    @Test
    fun activityLabelsStopAtTheThreeDocumentedClasses() {
        val cases = listOf(
            0 to ActivityKind.STILL,
            1 to ActivityKind.WALK,
            2 to ActivityKind.RUN,
            3 to null,
            0xFF to null,
        )
        for ((raw, shown) in cases) {
            val decoded = asLayout(frame { it[63] = raw.toByte() })
            assertEquals(shown, decoded.reading.activity, raw.toString())
            assertEquals(raw, decoded.stored.activityClassRaw)
            assertEquals(raw.takeIf { it in 0..2 }, decoded.stored.activityClass)
        }
    }

    @Test
    fun bandStateUsesBitsFourAndFiveOnly() {
        val states = listOf(BandMotion.WAKE, BandMotion.STILL, BandMotion.SLEEP, BandMotion.UP)
        for ((index, state) in states.withIndex()) {
            val motion = 0b1000_0011 or (index shl 4)
            val decoded = asLayout(frame { it[81] = motion.toByte() })
            assertEquals(state, decoded.reading.bandState)
            assertEquals(index, decoded.stored.restState)
            assertEquals(motion, decoded.stored.motionRaw)
        }
    }

    @Test
    fun retainedScalesStayOffTheDisplayWindow() {
        val kept = asLayout(
            frame {
                writeF32(it, 41, 0f)
                writeF32(it, 45, 8f)
                writeU16Le(it, 69, 0)
                writeU16Le(it, 71, 600)
            },
        )
        assertEquals(0f, kept.stored.dynamicAccel)
        assertEquals(8f, kept.stored.axisAccel[0])
        assertArrayEquals(floatArrayOf(0f, 60f), kept.stored.auxTempC, 0f)

        val nanBits = Float.NaN.toRawBits()
        val hidden = asLayout(
            frame {
                writeF32(it, 41, 9f)
                writeF32(it, 45, Float.POSITIVE_INFINITY)
                writeF32(it, 49, Float.NaN)
                writeF32(it, 53, -0.5f)
                writeU16Le(it, 69, -10)
                writeU16Le(it, 71, 610)
            },
        )
        assertNull(hidden.stored.dynamicAccel)
        assertEquals(9f.toRawBits(), hidden.stored.dynamicAccelBits)
        assertTrue(hidden.stored.axisAccel[0].isNaN())
        assertTrue(hidden.stored.axisAccel[1].isNaN())
        assertEquals(nanBits, hidden.stored.axisAccelBits[1])
        assertEquals(-0.5f, hidden.stored.axisAccel[2])
        assertTrue(hidden.stored.auxTempC[0].isNaN())
        assertTrue(hidden.stored.auxTempC[1].isNaN())
        assertArrayEquals(intArrayOf(-10, 610), hidden.stored.auxTempRaw)
        assertFalse(hidden.reading.toString().contains("alternate"))
    }

    @Test
    fun wrongLengthAndBadCrcAreRejected() {
        assertEquals(RecordRejectReason.WRONG_LENGTH, reject(ByteArray(0)))
        assertEquals(RecordRejectReason.WRONG_LENGTH, reject(ByteArray(9)))
        val shortLayout = ByteArray(10)
        shortLayout[9] = 18
        assertEquals(RecordRejectReason.WRONG_LENGTH, reject(shortLayout))
        assertEquals(RecordRejectReason.WRONG_LENGTH, reject(golden().copyOf(123)))
        val longFrame = golden() + byteArrayOf(0)
        assertEquals(RecordRejectReason.WRONG_LENGTH, reject(longFrame))

        val declared = golden()
        writeU16Le(declared, 2, 112)
        assertEquals(RecordRejectReason.WRONG_LENGTH, reject(declared))

        assertEquals(RecordRejectReason.BAD_CRC, reject(golden().flipped(50)))
        assertEquals(RecordRejectReason.BAD_CRC, reject(golden().flipped(120)))
        assertEquals(RecordRejectReason.BAD_CRC, reject(golden().flipped(4)))

        val badStart = golden()
        badStart[0] = 0
        assertEquals(RecordRejectReason.BAD_ENVELOPE, reject(badStart))
        val badFormat = golden()
        badFormat[1] = 0x40
        assertEquals(RecordRejectReason.BAD_ENVELOPE, reject(badFormat))
    }

    @Test
    fun unknownLayoutsArePreservedWhole() {
        val lengths = listOf(10, 88, 124, 188, 1244, 2140)
        val layouts = listOf(0, 20, 21, 22, 26, 255)
        for (layout in layouts) {
            for (length in lengths) {
                val bytes = ByteArray(length) { (it and 0xFF).toByte() }
                bytes[9] = layout.toByte()
                val decoded = HistoryRecordDecoder.decode(bytes)
                val unknown = assertInstanceOf(HistoryRecord.Unknown::class.java, decoded)
                assertEquals(layout, unknown.layout)
                assertArrayEquals(bytes, unknown.bytes)
                bytes[9] = 0
                assertEquals(layout.toByte(), unknown.bytes[9])
            }
        }
    }

    private fun asLayout(bytes: ByteArray): HistoryRecord.Layout18 {
        val decoded = HistoryRecordDecoder.decode(bytes)
        val layout = assertInstanceOf(HistoryRecord.Layout18::class.java, decoded)
        assertEquals(HistoryRecordDecoder.VERSION, layout.decoderVersion)
        return layout
    }

    private fun reject(bytes: ByteArray): RecordRejectReason {
        val decoded = HistoryRecordDecoder.decode(bytes)
        return assertInstanceOf(HistoryRecord.Rejected::class.java, decoded).reason
    }

    private fun golden(): ByteArray = frame {
        writeU32Le(it, 11, INDEX)
        writeU32Le(it, 15, UNIX)
        it[10] = 0x7E
        it[22] = 72
        it[23] = 4
        writeU16Le(it, 24, 1024)
        writeU16Le(it, 26, 1)
        writeU16Le(it, 28, 1079)
        writeU16Le(it, 30, 65535)
        it[32] = 0x11
        it[33] = 0xA5.toByte()
        it[34] = 0x55
        it[35] = 0x66
        it[36] = 0x3C
        it[37] = 99
        writeU16Le(it, 38, 0xBEEF)
        it[40] = 0x22
        writeF32(it, 41, 1.25f)
        writeF32(it, 45, -0.5f)
        writeF32(it, 49, 0.25f)
        writeF32(it, 53, 2f)
        writeU16Le(it, 57, 4000)
        writeU16Le(it, 59, 0x01FF)
        writeU16Le(it, 61, 0x0ABC)
        it[63] = 2
        it[64] = 0x10
        it[65] = 0x33
        writeU16Le(it, 69, 365)
        writeU16Le(it, 71, 200)
        writeU16Le(it, 73, 3650)
        writeU16Le(it, 75, 0x1111)
        writeU16Le(it, 77, 0x2222)
        writeU16Le(it, 79, 0x3333)
        it[81] = 0x6B
        it[82] = 0x5A
        it[83] = 0x44
    }

    private fun frame(fill: (ByteArray) -> Unit): ByteArray {
        val bytes = ByteArray(HistoryRecordDecoder.RECORD_BYTES)
        bytes[0] = 0xAA.toByte()
        bytes[1] = 0x01
        writeU16Le(bytes, 2, 116)
        writeU16Le(bytes, 4, HeaderWord.COMMON_REQUEST)
        bytes[8] = 47
        bytes[9] = HistoryRecordDecoder.LAYOUT.toByte()
        fill(bytes)
        writeU16Le(bytes, 6, Crc16Modbus.compute(bytes, 0, 6))
        writeU32Le(bytes, 120, Crc32IsoHdlc.compute(bytes, 8, 112))
        return bytes
    }

    private fun writeF32(bytes: ByteArray, offset: Int, value: Float) {
        writeU32Le(bytes, offset, value.toRawBits().toLong() and 0xFFFF_FFFFL)
    }

    private fun ByteArray.flipped(index: Int): ByteArray {
        val copy = copyOf()
        copy[index] = (copy[index].toInt() xor 0xFF).toByte()
        return copy
    }

    private companion object {
        const val INDEX: Long = 3_000_000_001L
        const val UNIX: Long = 1_735_689_600L
    }
}
