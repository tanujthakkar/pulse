package app.pulse.ble

/**
 * Bond of one strap address, as the platform reports it.
 *
 * [PRESENT] means this phone holds the bond. It does not prove the strap
 * still agrees: a stale bond fails later with an encryption error.
 */
enum class DeviceBond {
    ABSENT,
    BONDING,
    PRESENT,
}

/** Result of asking the platform to start a bond. No callback follows [NOT_STARTED] or [REFUSED]. */
enum class BondBegin {
    /** The platform is bonding. A later [BondEvents.onChanged] reports the result. */
    STARTED,

    /** The phone already holds a bond, so no new request was sent. */
    ALREADY_PRESENT,

    /** The platform refused before a request was sent. */
    REFUSED,

    /** The request did not start. The caller may try once more. */
    NOT_STARTED,
}

interface BondEvents {
    fun onChanged(address: String, bond: DeviceBond, refused: Boolean)
}

internal interface BondPort {
    var onUpdate: ((address: String, bond: DeviceBond, refused: Boolean) -> Unit)?

    fun state(address: String): DeviceBond

    fun createBond(address: String): Boolean

    fun cancel(address: String)
}

/**
 * Reads and starts the platform bond for one strap.
 *
 * The connect state machine decides how many times to call [begin]. This
 * class does not retry. [cancel] asks the port to drop an in-flight request;
 * a platform that cannot cancel still stops receiving new requests from us.
 */
class BondController internal constructor(
    private val port: BondPort,
    private val events: BondEvents,
) {
    private val runner = SerialRunner()
    private var active: String? = null

    init {
        port.onUpdate = { address, bond, refused ->
            runner.execute { events.onChanged(address, bond, refused) }
        }
    }

    fun state(address: String): DeviceBond = port.state(address)

    fun begin(address: String): BondBegin {
        active = address
        return when (port.state(address)) {
            DeviceBond.PRESENT -> BondBegin.ALREADY_PRESENT
            DeviceBond.BONDING -> BondBegin.STARTED
            DeviceBond.ABSENT -> {
                if (port.createBond(address)) BondBegin.STARTED else BondBegin.NOT_STARTED
            }
        }
    }

    fun cancel() {
        val address = active ?: return
        active = null
        port.cancel(address)
    }
}
