package app.pulse.ble

/**
 * Parses Heart Rate Measurement (`00002a37-0000-1000-8000-00805f9b34fb`).
 *
 * The layout is Heart Rate Service 1.0 section 3.1, summarized in protocol
 * spec §11. Bytes are little-endian (service section 1.7). Flags bit 0 selects
 * a `u8` or `u16` heart rate. Bits 1 and 2 are sensor contact. Bit 3 means an
 * Energy Expended `u16` follows and is skipped. Bit 4 means one or more R-R
 * `u16` values follow, each in units of 1/1024 s. Milliseconds use the project
 * rounding `(ticks * 1000 + 512) / 1024` so this path matches the later
 * proprietary intervals. Reserved flag bits are ignored.
 *
 * A short field or trailing bytes when R-R is absent reject the notification.
 */
object StandardHeartRateParser {
    fun parse(payload: ByteArray, tsMillis: Long): LiveHeartRate? {
        if (payload.isEmpty()) return null
        val flags = payload[0].toInt() and 0xFF
        var index = 1
        val wide = flags and HEART_RATE_FORMAT_U16 != 0
        val bpm = if (wide) {
            readU16(payload, index) ?: return null
        } else {
            readU8(payload, index) ?: return null
        }
        index += if (wide) 2 else 1
        if (flags and ENERGY_EXPENDED != 0) {
            if (readU16(payload, index) == null) return null
            index += 2
        }
        val rr = if (flags and RR_PRESENT != 0) {
            val remaining = payload.size - index
            if (remaining < 2 || remaining % 2 != 0) return null
            val intervals = ArrayList<Int>(remaining / 2)
            while (index < payload.size) {
                val ticks = readU16(payload, index) ?: return null
                index += 2
                intervals += rrTicksToMillis(ticks)
            }
            intervals
        } else if (index != payload.size) {
            return null
        } else {
            emptyList()
        }
        return LiveHeartRate(
            tsMillis = tsMillis,
            bpm = bpm,
            rrMs = rr,
            contact = contact(flags),
        )
    }

    /** Project rounding from protocol spec §11. The SIG unit is 1/1024 s. */
    internal fun rrTicksToMillis(ticks: Int): Int {
        return ((ticks.toLong() * 1000L + 512L) / 1024L).toInt()
    }

    private fun contact(flags: Int): SensorContact {
        if (flags and SENSOR_CONTACT_SUPPORTED == 0) return SensorContact.UNSUPPORTED
        return if (flags and SENSOR_CONTACT_DETECTED != 0) {
            SensorContact.DETECTED
        } else {
            SensorContact.NOT_DETECTED
        }
    }

    private fun readU8(payload: ByteArray, index: Int): Int? {
        if (index >= payload.size) return null
        return payload[index].toInt() and 0xFF
    }

    private fun readU16(payload: ByteArray, index: Int): Int? {
        if (index + 1 >= payload.size) return null
        val low = payload[index].toInt() and 0xFF
        val high = payload[index + 1].toInt() and 0xFF
        return low or (high shl 8)
    }

    private const val HEART_RATE_FORMAT_U16: Int = 0x01
    private const val SENSOR_CONTACT_DETECTED: Int = 0x02
    private const val SENSOR_CONTACT_SUPPORTED: Int = 0x04
    private const val ENERGY_EXPENDED: Int = 0x08
    private const val RR_PRESENT: Int = 0x10
}
