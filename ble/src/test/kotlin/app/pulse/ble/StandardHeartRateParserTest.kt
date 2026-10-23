package app.pulse.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Payloads built from Heart Rate Service 1.0 section 3.1.
 *
 * Flags: bit 0 u8/u16 heart rate, bits 1–2 sensor contact, bit 3 energy
 * expended, bit 4 R-R. Multi-byte fields are little-endian (section 1.7).
 * R-R milliseconds are `(ticks * 1000 + 512) / 1024` (protocol spec §11).
 */
class StandardHeartRateParserTest {
    @Test
    fun uint8HeartRateWithoutOptionalFields() {
        val sample = parse(0x00, 0x3C)
        assertEquals(60, sample.bpm)
        assertEquals(emptyList<Int>(), sample.rrMs)
        assertEquals(SensorContact.UNSUPPORTED, sample.contact)
        assertEquals(TS, sample.tsMillis)
    }

    @Test
    fun uint16HeartRateWithoutOptionalFields() {
        // 300 bpm does not fit in a u8, so bit 0 is set. Little-endian 0x012C.
        val sample = parse(0x01, 0x2C, 0x01)
        assertEquals(300, sample.bpm)
        assertEquals(emptyList<Int>(), sample.rrMs)
        assertEquals(SensorContact.UNSUPPORTED, sample.contact)
    }

    @Test
    fun uint16ValueJustAboveTheUint8Range() {
        val sample = parse(0x01, 0x00, 0x01)
        assertEquals(256, sample.bpm)
    }

    @Test
    fun sensorContactBitsFollowTheServiceSpec() {
        val cases = listOf(
            0x00 to SensorContact.UNSUPPORTED,
            0x02 to SensorContact.UNSUPPORTED,
            0x04 to SensorContact.NOT_DETECTED,
            0x06 to SensorContact.DETECTED,
        )
        for ((flags, contact) in cases) {
            val sample = parse(flags, 0x48)
            assertEquals(contact, sample.contact, flags.toString(16))
            assertEquals(72, sample.bpm)
            assertEquals(emptyList<Int>(), sample.rrMs)
        }
    }

    @Test
    fun energyExpendedIsSkippedForUint8AndUint16() {
        // bit 3 set, energy 300 kJ (0x012C), no R-R. Heart rate stays 60.
        val narrow = parse(0x08, 0x3C, 0x2C, 0x01)
        assertEquals(60, narrow.bpm)
        assertEquals(emptyList<Int>(), narrow.rrMs)

        // bit 0 and bit 3. Heart rate 180 (0x00B4), energy 10.
        val wide = parse(0x09, 0xB4, 0x00, 0x0A, 0x00)
        assertEquals(180, wide.bpm)
        assertEquals(emptyList<Int>(), wide.rrMs)
    }

    @Test
    fun saturatedEnergyExpendedDoesNotHideTheHeartRate() {
        // Spec section 3.1.1.3: energy stays at 0xFFFF once it saturates.
        val sample = parse(0x08, 0x50, 0xFF, 0xFF)
        assertEquals(80, sample.bpm)
        assertEquals(emptyList<Int>(), sample.rrMs)
    }

    @Test
    fun rrIntervalsConvertWithProjectRounding() {
        // bit 4, u8 heart rate 60. Ticks 1024 and 512 -> 1000 ms and 500 ms.
        val pair = parse(0x10, 0x3C, 0x00, 0x04, 0x00, 0x02)
        assertEquals(60, pair.bpm)
        assertEquals(listOf(1000, 500), pair.rrMs)
        assertEquals(SensorContact.UNSUPPORTED, pair.contact)

        // One tick rounds to 1 ms: (1 * 1000 + 512) / 1024.
        val one = parse(0x10, 0x3C, 0x01, 0x00)
        assertEquals(listOf(1), one.rrMs)

        // Zero ticks is a legal u16 and rounds to 0 ms.
        val zero = parse(0x10, 0x3C, 0x00, 0x00)
        assertEquals(listOf(0), zero.rrMs)

        // 1079 and 775 ticks, the two-interval layout from the field widths.
        val measured = parse(0x10, 0x38, 0x37, 0x04, 0x07, 0x03)
        assertEquals(56, measured.bpm)
        assertEquals(listOf(1054, 757), measured.rrMs)
    }

    @Test
    fun uint8WithEnergyAndRrSkipsTheEnergyField() {
        // bits 3 and 4. Energy 1000 (0x03E8), one R-R of 1024 ticks.
        val sample = parse(0x18, 0x3C, 0xE8, 0x03, 0x00, 0x04)
        assertEquals(60, sample.bpm)
        assertEquals(listOf(1000), sample.rrMs)
    }

    @Test
    fun uint16WithContactEnergyAndRr() {
        // bits 0, 1, 2, 3, 4. Contact detected. HR 180. Energy 10. RR 1024 ticks.
        val sample = parse(0x1F, 0xB4, 0x00, 0x0A, 0x00, 0x00, 0x04)
        assertEquals(180, sample.bpm)
        assertEquals(SensorContact.DETECTED, sample.contact)
        assertEquals(listOf(1000), sample.rrMs)
    }

    @Test
    fun uint16WithContactNotDetectedAndTwoRrIntervals() {
        // bit 0, bit 2, bit 4. HR 55. Ticks 853 -> 833 ms.
        val sample = parse(0x15, 0x37, 0x00, 0x55, 0x03)
        assertEquals(55, sample.bpm)
        assertEquals(SensorContact.NOT_DETECTED, sample.contact)
        assertEquals(listOf(833), sample.rrMs)
    }

    @Test
    fun reservedFlagBitsDoNotHideTheHeartRate() {
        // Bits 5–7 are reserved and shall be 0 on the sensor. A client still
        // reads the fields this service defines.
        val sample = parse(0xE0, 0x3C)
        assertEquals(60, sample.bpm)
        assertEquals(SensorContact.UNSUPPORTED, sample.contact)
    }

    @Test
    fun maximumRrTickUsesTheFullUint16() {
        val sample = parse(0x10, 0x3C, 0xFF, 0xFF)
        assertEquals(listOf(63999), sample.rrMs)
        assertEquals(63999, StandardHeartRateParser.rrTicksToMillis(65535))
    }

    @Test
    fun truncatedAndTrailingPayloadsAreRejected() {
        assertNull(StandardHeartRateParser.parse(byteArrayOf(), TS))
        assertNull(parseOrNull(0x00))
        assertNull(parseOrNull(0x01, 0x2C))
        assertNull(parseOrNull(0x08, 0x3C, 0x2C))
        assertNull(parseOrNull(0x10, 0x3C))
        assertNull(parseOrNull(0x10, 0x3C, 0x00))
        assertNull(parseOrNull(0x00, 0x3C, 0x00))
        assertNull(parseOrNull(0x09, 0xB4, 0x00, 0x0A))
    }

    private fun parse(vararg bytes: Int): LiveHeartRate {
        val sample = parseOrNull(*bytes)
        return checkNotNull(sample) { "expected a measurement from ${bytes.toList()}" }
    }

    private fun parseOrNull(vararg bytes: Int): LiveHeartRate? {
        return StandardHeartRateParser.parse(bytes.map { it.toByte() }.toByteArray(), TS)
    }

    private companion object {
        const val TS: Long = 1_700_000_000_000L
    }
}
