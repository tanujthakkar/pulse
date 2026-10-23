package app.pulse.link

/** Firmware Revision String, UTF-8, with trailing NULs and whitespace removed. */
fun firmwareRevisionText(value: ByteArray): String? {
    if (value.isEmpty()) return null
    val text = value.toString(Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
    return text.ifEmpty { null }
}

/** Battery Level is one byte, whole percent. */
fun batteryServicePercent(value: ByteArray): Int? {
    if (value.isEmpty()) return null
    return value[0].toInt() and 0xFF
}

fun helloFirmwareLine(version: String?): String? = version?.let { "Firmware (hello): $it" }

fun informationFirmwareLine(version: String?): String? =
    version?.let { "Firmware (device information): $it" }

fun commandBatteryLine(percent: Long?): String? = percent?.let { "Battery (command 26): $it%" }

fun serviceBatteryLine(percent: Int?): String? =
    percent?.let { "Battery (standard battery service): $it%" }

/**
 * Present only when both version strings were read. The two lines are still
 * shown on their own when only one source is present.
 */
fun firmwareNote(hello: String?, information: String?): String? {
    if (hello.isNullOrBlank() || information.isNullOrBlank()) return null
    return if (hello == information) {
        "Firmware strings match."
    } else {
        "Firmware strings differ."
    }
}

fun handshakeLine(hello: HelloProgress, heartRateLive: Boolean): String? = when (hello) {
    HelloProgress.IDLE -> null
    HelloProgress.WAITING -> "Saying hello."
    HelloProgress.SUCCEEDED -> "Hello succeeded."
    HelloProgress.FAILED -> if (heartRateLive) {
        "Hello failed. Live heart rate is still on."
    } else {
        "Hello failed."
    }
}
