package app.pulse.ble

/**
 * One Heart Rate Measurement notification.
 *
 * [bpm] is the value carried in the characteristic, including readings the
 * Live screen will not draw. [rrMs] is each R-R interval in milliseconds.
 * [tsMillis] is when this phone received the notification: the characteristic
 * itself has no timestamp (Heart Rate Service 1.0 section 3.4).
 *
 * Milestone 1 keeps this in memory only.
 */
data class LiveHeartRate(
    val tsMillis: Long,
    val bpm: Int,
    val rrMs: List<Int>,
    val contact: SensorContact,
)

/**
 * Sensor Contact Status bits, Heart Rate Service 1.0 section 3.1.1.1.2.
 *
 * Bit 2 is the support bit. When it is clear, contact is not reported.
 * When it is set, bit 1 is 0 for no or poor skin contact and 1 for contact.
 */
enum class SensorContact {
    UNSUPPORTED,
    NOT_DETECTED,
    DETECTED,
}
