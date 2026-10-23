package app.pulse.link

import app.pulse.ble.BondBegin
import app.pulse.ble.DeviceBond

/**
 * Connect and bond phases for one strap.
 *
 * [PAIRING_HELP] means another phone still holds the strap's only bond.
 * The machine reaches it on a refusal or when bonding is still unfinished
 * [StrapLinkLimits.PAIRING_HELP_AFTER_MS] after it started, and it does not
 * keep requesting bonds on its own.
 */
enum class LinkPhase {
    IDLE,
    SCANNING,
    BONDING,
    CONNECTING,
    LINKED,
    PAIRING_HELP,
    FAILED,
}

/** Why a terminal phase was entered. [NONE] while the link is still working. */
enum class LinkBlock {
    NONE,
    SCAN,
    BOND,
    CONNECT,
}

data class LinkSnapshot(
    val phase: LinkPhase = LinkPhase.IDLE,
    val address: String? = null,
    val bondAttempt: Int = 0,
    val connectAttempt: Int = 0,
    val block: LinkBlock = LinkBlock.NONE,
    val scanFailure: Int? = null,
    val bluetoothOff: Boolean = false,
)

object StrapLinkLimits {
    const val BOND_ATTEMPTS: Int = 2
    const val CONNECT_ATTEMPTS: Int = 2

    /** Stuck-bond ceiling. Pairing help is shown at this deadline, not after it. */
    const val PAIRING_HELP_AFTER_MS: Long = 15_000
}

/**
 * ATT error codes that mean the proprietary channel is closed.
 *
 * Protocol spec §3 quotes the strap as "Encryption is insufficient" and
 * "bond refused" while another central holds the bond. 0x05 is Insufficient
 * Authentication and 0x0F is Insufficient Encryption.
 */
object SessionFault {
    const val INSUFFICIENT_AUTHENTICATION: Int = 5
    const val INSUFFICIENT_ENCRYPTION: Int = 15

    fun otherCentralHoldsBond(status: Int): Boolean {
        return status == INSUFFICIENT_AUTHENTICATION || status == INSUFFICIENT_ENCRYPTION
    }
}

interface LinkPorts {
    fun startScan()

    fun stopScan()

    fun bondOf(address: String): DeviceBond

    fun beginBond(address: String): BondBegin

    fun cancelBond()

    fun openSession(address: String)

    fun closeSession()
}

fun interface LinkAlarm {
    fun cancel()
}

interface LinkClock {
    fun after(delayMs: Long, task: () -> Unit): LinkAlarm
}
