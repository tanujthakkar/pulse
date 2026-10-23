package app.pulse.wire.codec

/**
 * Battery level, command 26.
 *
 * Spec §7. The current query is described as asynchronous. Older requests use
 * an empty body or `00`. [REQUEST_BODY] is empty. On the wire a one-byte pad
 * can occupy the same place as an explicit `00`; the encoder still receives
 * the empty body.
 *
 * The final body is a little-endian `u32` whole percent. A result of 0 is not
 * parsed here. A parsed zero can be a conversion fallback, so callers must not
 * treat it as proof the battery is empty.
 */
object BatteryCommand {
    const val NUMBER: Int = 26

    val REQUEST_BODY: ByteArray = ByteArray(0)

    fun percent(body: ByteArray): Long? {
        if (body.size < 4) return null
        return readU32Le(body, 0)
    }
}
