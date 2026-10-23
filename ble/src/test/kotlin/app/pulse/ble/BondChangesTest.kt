package app.pulse.ble

import android.bluetooth.BluetoothDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BondChangesTest {
    @Test
    fun platformStatesMapOntoTheBondPort() {
        assertEquals(DeviceBond.ABSENT, BondChanges.fromPlatform(BluetoothDevice.BOND_NONE))
        assertEquals(DeviceBond.BONDING, BondChanges.fromPlatform(BluetoothDevice.BOND_BONDING))
        assertEquals(DeviceBond.PRESENT, BondChanges.fromPlatform(BluetoothDevice.BOND_BONDED))
        assertEquals(DeviceBond.ABSENT, BondChanges.fromPlatform(BluetoothDevice.ERROR))
    }

    @Test
    fun bondedTransitionIsNotARefusal() {
        val update = BondChanges.classify(
            previous = BluetoothDevice.BOND_BONDING,
            current = BluetoothDevice.BOND_BONDED,
            reason = 0,
        )
        assertEquals(DeviceBond.PRESENT, update?.bond)
        assertFalse(update!!.refused)
    }

    @Test
    fun rejectedAndEncryptionFailuresAreRefusals() {
        listOf(
            BondReason.AUTH_FAILED,
            BondReason.AUTH_REJECTED,
            BondReason.REMOTE_AUTH_CANCELED,
            BondReason.REPEATED_ATTEMPTS,
        ).forEach { reason ->
            val update = BondChanges.classify(
                previous = BluetoothDevice.BOND_BONDING,
                current = BluetoothDevice.BOND_NONE,
                reason = reason,
            )
            assertEquals(DeviceBond.ABSENT, update?.bond, "reason $reason")
            assertTrue(update!!.refused, "reason $reason")
        }
    }

    @Test
    fun timeoutAndLocalCancelAreNotRefusals() {
        listOf(0, 3, 4, 6).forEach { reason ->
            val update = BondChanges.classify(
                previous = BluetoothDevice.BOND_BONDING,
                current = BluetoothDevice.BOND_NONE,
                reason = reason,
            )
            assertEquals(DeviceBond.ABSENT, update?.bond)
            assertFalse(update!!.refused, "reason $reason")
        }
    }

    @Test
    fun movingIntoBondingIsInProgress() {
        val update = BondChanges.classify(
            previous = BluetoothDevice.BOND_NONE,
            current = BluetoothDevice.BOND_BONDING,
            reason = 0,
        )
        assertEquals(DeviceBond.BONDING, update?.bond)
        assertFalse(update!!.refused)
    }

    @Test
    fun missingCurrentStateIsIgnored() {
        assertNull(
            BondChanges.classify(
                previous = BluetoothDevice.BOND_BONDING,
                current = BluetoothDevice.ERROR,
                reason = BondReason.AUTH_REJECTED,
            ),
        )
    }
}
