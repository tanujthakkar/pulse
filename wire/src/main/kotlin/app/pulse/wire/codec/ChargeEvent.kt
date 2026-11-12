package app.pulse.wire.codec

/**
 * Charging from an event frame.
 *
 * Catalogue event 7 is charging on and event 8 is charging off. The event
 * number is frame byte 10. Command 26 and Battery Level `2A19` carry a
 * percent only, and a percent is not treated as charging. The catalogue
 * names are not a capture from firmware 50.43.1.0, so a strap on that build
 * may never emit these events. This parser does not fill in a value when
 * the frame is something else.
 */
object ChargeEvent {
    const val ON: Int = 7
    const val OFF: Int = 8

    fun charging(frame: WireFrame): Boolean? {
        if (frame.packetType != PacketType.EVENT) return null
        return when (frame.command) {
            ON -> true
            OFF -> false
            else -> null
        }
    }
}
