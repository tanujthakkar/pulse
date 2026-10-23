package app.pulse.ble

import java.util.UUID

/**
 * Observes characteristic payloads as they pass the session.
 *
 * The session ignores exceptions from this callback so a debug recorder
 * cannot block a write or a notification. The callback must not transmit.
 */
fun interface FrameTap {
    fun onPayload(incoming: Boolean, characteristic: UUID, value: ByteArray, atMillis: Long)
}

/**
 * Short label for the debug list.
 *
 * A Bluetooth base UUID keeps the 16-bit id (`00002a37-...` becomes `2a37`).
 * Other UUIDs keep the first group (`fd4b0003-...` becomes `fd4b0003`).
 */
fun characteristicLabel(characteristic: UUID): String {
    val token = characteristic.toString().substringBefore('-')
    return if (token.startsWith("0000") && token.length >= 8) {
        token.substring(4, 8)
    } else {
        token
    }
}
