package app.pulse.ble

import android.bluetooth.BluetoothDevice

/**
 * Android bond-state reason values. The public bond broadcast carries the
 * current and previous state; the reason is delivered under
 * [REASON_EXTRA]. Refusal reasons match the pairing note in protocol spec
 * §3: the strap reports "bond refused" or "Encryption is insufficient"
 * while another central holds the only bond.
 */
internal object BondReason {
    const val REASON_EXTRA: String = "android.bluetooth.device.extra.REASON"

    const val AUTH_FAILED: Int = 1
    const val AUTH_REJECTED: Int = 2
    const val REMOTE_AUTH_CANCELED: Int = 8
    const val REPEATED_ATTEMPTS: Int = 7

    fun isRefusal(reason: Int): Boolean = when (reason) {
        AUTH_FAILED,
        AUTH_REJECTED,
        REMOTE_AUTH_CANCELED,
        REPEATED_ATTEMPTS,
        -> true
        else -> false
    }
}

internal data class BondUpdate(
    val bond: DeviceBond,
    val refused: Boolean,
)

internal object BondChanges {
    fun fromPlatform(state: Int): DeviceBond = when (state) {
        BluetoothDevice.BOND_BONDING -> DeviceBond.BONDING
        BluetoothDevice.BOND_BONDED -> DeviceBond.PRESENT
        else -> DeviceBond.ABSENT
    }

    fun classify(previous: Int, current: Int, reason: Int): BondUpdate? {
        if (current == BluetoothDevice.ERROR) return null
        val bond = fromPlatform(current)
        val refused = previous == BluetoothDevice.BOND_BONDING &&
            current == BluetoothDevice.BOND_NONE &&
            BondReason.isRefusal(reason)
        return BondUpdate(bond, refused)
    }
}
