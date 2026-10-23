package app.pulse.feature.connect

import app.pulse.ble.BondBegin
import app.pulse.ble.DeviceBond
import app.pulse.ble.StrapSighting
import app.pulse.link.LinkAlarm
import app.pulse.link.LinkClock
import app.pulse.link.LinkPhase
import app.pulse.link.LinkPorts
import app.pulse.link.StrapLink
import app.pulse.link.StrapLinkLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectViewModelTest {
    @Test
    fun grantPathReachesConnectWhenBluetoothIsOn() {
        val memory = Memory()
        val vm = ConnectViewModel(memory, MutableProbe(fresh(33)), quietLink())
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
        val vm = ConnectViewModel(memory, MutableProbe(fresh(33)), quietLink())
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
        val vm = ConnectViewModel(memory, MutableProbe(fresh(35)), quietLink())
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
        val vm = ConnectViewModel(Memory(), MutableProbe(granted(32, bluetoothEnabled = false)), quietLink())
        vm.publish(granted(32, bluetoothEnabled = false))
        assertEquals(ConnectUi.BluetoothOff, vm.ui.value)
    }

    @Test
    fun resultMapCanGrantBeforeTheProbeCatchesUp() {
        val vm = ConnectViewModel(Memory(), MutableProbe(fresh(33)), quietLink())
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
        val vm = ConnectViewModel(Memory(), MutableProbe(fresh(31)), quietLink())
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

    @Test
    fun readyStartsTheLinkAndPairingHelpDoesNotLoop() {
        val ports = RecordingPorts()
        val clock = RecordingClock()
        val link = StrapLink(ports, clock)
        val granted = granted(33, bluetoothEnabled = true)
        val vm = ConnectViewModel(Memory(), MutableProbe(granted), link)

        assertEquals(LinkPhase.IDLE, link.snapshot().phase)
        vm.publish(granted)
        assertEquals(ConnectUi.Ready, vm.ui.value)
        assertEquals(LinkPhase.SCANNING, link.snapshot().phase)
        assertEquals("Looking for a strap.", vm.linkPanel.value.status)
        assertTrue(vm.linkPanel.value.showStop)

        vm.publish(granted)
        assertEquals(LinkPhase.SCANNING, link.snapshot().phase)
        assertEquals(1, ports.scans)

        ports.bond = DeviceBond.ABSENT
        link.onSighting(StrapSighting("AA:BB:CC:DD:EE:FF", -20, null))
        assertTrue(vm.linkPanel.value.status.startsWith("Bonding, attempt 1"))

        clock.fire()
        assertEquals(LinkPhase.PAIRING_HELP, link.snapshot().phase)
        assertTrue(vm.linkPanel.value.pairingHelp)
        assertTrue(vm.linkPanel.value.helpBody.contains("flash blue"))
        assertEquals(1, ports.bonds.size)

        vm.retryLink()
        assertEquals("Looking for a strap.", vm.linkPanel.value.status)
        assertFalse(vm.linkPanel.value.pairingHelp)

        vm.stopLink()
        assertEquals("Ready to look for a strap.", vm.linkPanel.value.status)
        assertFalse(vm.linkPanel.value.showStop)

        vm.publish(granted)
        assertEquals(LinkPhase.IDLE, link.snapshot().phase)
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

private class RecordingPorts : LinkPorts {
    var bond: DeviceBond = DeviceBond.ABSENT
    val bonds = mutableListOf<String>()
    var scans: Int = 0

    override fun startScan() {
        scans += 1
    }

    override fun stopScan() = Unit

    override fun bondOf(address: String): DeviceBond = bond

    override fun beginBond(address: String): BondBegin {
        bonds += address
        return BondBegin.STARTED
    }

    override fun cancelBond() = Unit

    override fun openSession(address: String) = Unit

    override fun closeSession() = Unit

    override fun watchHeartRate(address: String) = Unit
}

private class RecordingClock : LinkClock {
    private var task: (() -> Unit)? = null

    override fun after(delayMs: Long, task: () -> Unit): LinkAlarm {
        assertEquals(StrapLinkLimits.PAIRING_HELP_AFTER_MS, delayMs)
        this.task = task
        return LinkAlarm { this.task = null }
    }

    fun fire() {
        val pending = task
        task = null
        pending?.invoke()
    }
}
