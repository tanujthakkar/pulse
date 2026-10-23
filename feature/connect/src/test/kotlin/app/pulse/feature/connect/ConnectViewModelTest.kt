package app.pulse.feature.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectViewModelTest {
    @Test
    fun grantPathReachesConnectWhenBluetoothIsOn() {
        val memory = Memory()
        val vm = ConnectViewModel(memory, MutableProbe(fresh(33)))
        vm.publish(fresh(33))
        assertEquals(
            ConnectUi.NeedsGrant(LinkPermission.requiredFor(33)),
            vm.ui.value,
        )

        val granted = granted(33, bluetoothEnabled = true)
        vm.onPermissionResult(
            snapshot = granted,
            requested = LinkPermission.requiredFor(33),
            result = granted.granted.associate { it.androidPermission to true },
        )

        assertEquals(ConnectUi.Ready, vm.ui.value)
        assertEquals("0:0", vm.modulePins)
    }

    @Test
    fun denialMovesToRationaleAndRemembersThePrompt() {
        val memory = Memory()
        val vm = ConnectViewModel(memory, MutableProbe(fresh(33)))
        val required = LinkPermission.requiredFor(33)
        vm.onPermissionResult(
            snapshot = fresh(33),
            requested = required,
            result = required.associate { it.androidPermission to false },
        )

        val ui = vm.ui.value
        assertEquals(ConnectUi.Rationale(required, canAskAgain = false), ui)
        assertEquals(required.toSet(), memory.prompted())
    }

    @Test
    fun returningFromSettingsWithGrantsContinues() {
        val memory = Memory()
        val vm = ConnectViewModel(memory, MutableProbe(fresh(35)))
        val required = LinkPermission.requiredFor(35)
        vm.onPermissionResult(
            fresh(35),
            required,
            required.associate { it.androidPermission to false },
        )
        assertTrue(vm.ui.value is ConnectUi.Rationale)

        vm.publish(granted(35, bluetoothEnabled = true))
        assertEquals(ConnectUi.Ready, vm.ui.value)
    }

    @Test
    fun grantedWithBluetoothOffShowsEnablePrompt() {
        val vm = ConnectViewModel(Memory(), MutableProbe(granted(32, bluetoothEnabled = false)))
        vm.publish(granted(32, bluetoothEnabled = false))
        assertEquals(ConnectUi.BluetoothOff, vm.ui.value)
    }

    @Test
    fun resultMapCanGrantBeforeTheProbeCatchesUp() {
        val vm = ConnectViewModel(Memory(), MutableProbe(fresh(33)))
        val required = LinkPermission.requiredFor(33)
        vm.onPermissionResult(
            snapshot = fresh(33).copy(bluetoothEnabled = true),
            requested = required,
            result = required.associate { it.androidPermission to true },
        )
        assertEquals(ConnectUi.Ready, vm.ui.value)
    }

    @Test
    fun api31GrantDoesNotRequireNotifications() {
        val vm = ConnectViewModel(Memory(), MutableProbe(fresh(31)))
        vm.onPermissionResult(
            snapshot = fresh(31).copy(
                granted = setOf(LinkPermission.Scan, LinkPermission.Connect),
                bluetoothEnabled = true,
            ),
            requested = listOf(LinkPermission.Scan, LinkPermission.Connect),
            result = mapOf(
                LinkPermission.Scan.androidPermission to true,
                LinkPermission.Connect.androidPermission to true,
            ),
        )
        assertEquals(ConnectUi.Ready, vm.ui.value)
    }

    private fun fresh(sdk: Int) = RadioSnapshot(
        sdkInt = sdk,
        granted = emptySet(),
        rationale = emptySet(),
        bluetoothEnabled = true,
    )

    private fun granted(sdk: Int, bluetoothEnabled: Boolean) = RadioSnapshot(
        sdkInt = sdk,
        granted = LinkPermission.requiredFor(sdk).toSet(),
        rationale = emptySet(),
        bluetoothEnabled = bluetoothEnabled,
    )
}
