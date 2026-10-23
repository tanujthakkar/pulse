package app.pulse.link

import app.pulse.ble.BondBegin
import app.pulse.ble.CloseReason
import app.pulse.ble.DeviceBond
import app.pulse.ble.ScanFailure
import app.pulse.ble.StrapSighting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StrapLinkTransitionTest {
    @Test
    fun startMovesIdleToScanning() {
        val fx = Fixture()
        fx.link.start()
        assertEquals(listOf(LinkPhase.IDLE, LinkPhase.SCANNING), fx.phases)
        assertTrue(fx.ports.scanning)
        assertTrue(fx.session.opened.isEmpty())
    }

    @Test
    fun stopWhileScanningReturnsToIdle() {
        val fx = Fixture()
        fx.link.start()
        fx.link.stop()
        assertEquals(LinkPhase.IDLE, fx.link.snapshot().phase)
        assertFalse(fx.ports.scanning)
        fx.link.onSighting(sighting())
        assertTrue(fx.session.opened.isEmpty())
        assertTrue(fx.ports.bonds.isEmpty())
    }

    @Test
    fun sightingWhenAlreadyBondedOpensTheSession() {
        val fx = Fixture()
        fx.link.start()
        fx.sight(DeviceBond.PRESENT)
        assertEquals(LinkPhase.CONNECTING, fx.link.snapshot().phase)
        assertEquals(listOf(ADDRESS), fx.ports.heartRateWatches)
        assertEquals(listOf(ADDRESS), fx.session.opened)
        assertTrue(fx.ports.bonds.isEmpty())
        assertFalse(fx.ports.scanning)
        assertEquals(ADDRESS, fx.link.snapshot().address)
    }

    @Test
    fun sightingWhenUnbondedStartsBonding() {
        val fx = Fixture()
        fx.link.start()
        fx.sight(DeviceBond.ABSENT)
        assertEquals(LinkPhase.BONDING, fx.link.snapshot().phase)
        assertEquals(listOf(ADDRESS), fx.ports.bonds)
        assertEquals(1, fx.link.snapshot().bondAttempt)
        assertEquals(listOf(ADDRESS), fx.ports.heartRateWatches)
        assertTrue(fx.session.opened.isEmpty())
        assertFalse(fx.ports.scanning)
    }

    @Test
    fun sightingWatchesHeartRateBeforeBondingFinishes() {
        val fx = Fixture()
        fx.link.start()
        fx.sight(DeviceBond.ABSENT)
        assertEquals(listOf(ADDRESS), fx.ports.heartRateWatches)
        assertTrue(fx.session.opened.isEmpty())
        fx.link.onBondChanged(ADDRESS, DeviceBond.PRESENT, refused = false)
        assertEquals(listOf(ADDRESS), fx.session.opened)
        assertEquals(listOf(ADDRESS), fx.ports.heartRateWatches)
    }

    @Test
    fun sightingWhileABondIsAlreadyInProgressWaits() {
        val fx = Fixture()
        fx.link.start()
        fx.sight(DeviceBond.BONDING)
        assertEquals(LinkPhase.BONDING, fx.link.snapshot().phase)
        assertTrue(fx.ports.bonds.isEmpty())
        assertEquals(1, fx.link.snapshot().bondAttempt)
        fx.link.onBondChanged(ADDRESS, DeviceBond.PRESENT, refused = false)
        assertEquals(listOf(ADDRESS), fx.session.opened)
        assertEquals(LinkPhase.CONNECTING, fx.link.snapshot().phase)
    }

    @Test
    fun scanFailureMovesToFailedAndDoesNotBond() {
        val fx = Fixture()
        fx.link.start()
        fx.link.onScanFailed(ScanFailure.ADAPTER_OFF)
        assertEquals(LinkPhase.FAILED, fx.link.snapshot().phase)
        assertEquals(LinkBlock.SCAN, fx.link.snapshot().block)
        assertEquals(ScanFailure.ADAPTER_OFF, fx.link.snapshot().scanFailure)
        assertTrue(fx.link.snapshot().bluetoothOff)
        assertFalse(fx.ports.scanning)
        assertTrue(fx.ports.bonds.isEmpty())
        assertTrue(fx.session.opened.isEmpty())
    }

    @Test
    fun sightingIgnoredAfterLeavingScanning() {
        val fx = Fixture()
        fx.link.start()
        fx.sight(DeviceBond.ABSENT)
        val bonds = fx.ports.bonds.size
        fx.link.onSighting(sighting("11:22:33:44:55:66"))
        assertEquals(bonds, fx.ports.bonds.size)
        assertEquals(ADDRESS, fx.link.snapshot().address)
    }

    @Test
    fun blankAddressIsIgnored() {
        val fx = Fixture()
        fx.link.start()
        fx.link.onSighting(StrapSighting("  ", -1, null))
        fx.link.onSighting(StrapSighting("", -1, null))
        assertEquals(LinkPhase.SCANNING, fx.link.snapshot().phase)
        assertTrue(fx.ports.bonds.isEmpty())
    }

    @Test
    fun bondSuccessOpensTheSession() {
        val fx = Fixture()
        fx.bondUntilWaiting()
        fx.link.onBondChanged(ADDRESS, DeviceBond.PRESENT, refused = false)
        assertEquals(LinkPhase.CONNECTING, fx.link.snapshot().phase)
        assertEquals(listOf(ADDRESS), fx.session.opened)
        assertEquals(1, fx.ports.bonds.size)
    }

    @Test
    fun transientBondFailureTriesOnceMore() {
        val fx = Fixture()
        fx.bondUntilWaiting()
        fx.link.onBondChanged(ADDRESS, DeviceBond.ABSENT, refused = false)
        assertEquals(LinkPhase.BONDING, fx.link.snapshot().phase)
        assertEquals(listOf(ADDRESS, ADDRESS), fx.ports.bonds)
        assertEquals(2, fx.link.snapshot().bondAttempt)
        assertTrue(fx.session.opened.isEmpty())
    }

    @Test
    fun secondTransientBondFailureStops() {
        val fx = Fixture()
        fx.bondUntilWaiting()
        fx.link.onBondChanged(ADDRESS, DeviceBond.ABSENT, refused = false)
        fx.link.onBondChanged(ADDRESS, DeviceBond.ABSENT, refused = false)
        assertEquals(LinkPhase.FAILED, fx.link.snapshot().phase)
        assertEquals(LinkBlock.BOND, fx.link.snapshot().block)
        assertEquals(2, fx.ports.bonds.size)
        assertTrue(fx.session.opened.isEmpty())
        fx.clock.advance(60_000)
        assertEquals(2, fx.ports.bonds.size)
        assertEquals(LinkPhase.FAILED, fx.link.snapshot().phase)
    }

    @Test
    fun refusedBondShowsPairingHelpWithoutAnotherAttempt() {
        val fx = Fixture()
        fx.bondUntilWaiting()
        fx.link.onBondChanged(ADDRESS, DeviceBond.ABSENT, refused = true)
        assertEquals(LinkPhase.PAIRING_HELP, fx.link.snapshot().phase)
        assertEquals(LinkBlock.BOND, fx.link.snapshot().block)
        assertEquals(1, fx.ports.bonds.size)
        assertFalse(fx.session.open)
        fx.clock.advance(60_000)
        fx.link.onBondChanged(ADDRESS, DeviceBond.ABSENT, refused = true)
        fx.link.onBondChanged(ADDRESS, DeviceBond.PRESENT, refused = false)
        assertEquals(1, fx.ports.bonds.size)
        assertTrue(fx.session.opened.isEmpty())
        assertEquals(LinkPhase.PAIRING_HELP, fx.link.snapshot().phase)
    }

    @Test
    fun bondingDeadlineShowsPairingHelpAndDoesNotRetry() {
        val fx = Fixture()
        fx.bondUntilWaiting()
        fx.clock.advance(StrapLinkLimits.PAIRING_HELP_AFTER_MS - 1)
        assertEquals(LinkPhase.BONDING, fx.link.snapshot().phase)
        fx.clock.advance(1)
        assertEquals(LinkPhase.PAIRING_HELP, fx.link.snapshot().phase)
        assertEquals(1, fx.ports.bonds.size)
        assertTrue(fx.ports.cancels.isNotEmpty())
        fx.clock.advance(StrapLinkLimits.PAIRING_HELP_AFTER_MS)
        assertEquals(1, fx.ports.bonds.size)
        assertEquals(LinkPhase.PAIRING_HELP, fx.link.snapshot().phase)
    }

    @Test
    fun deadlineStillAppliesAcrossTheSecondAttempt() {
        val fx = Fixture()
        fx.bondUntilWaiting()
        fx.clock.advance(1_000)
        fx.link.onBondChanged(ADDRESS, DeviceBond.ABSENT, refused = false)
        assertEquals(2, fx.ports.bonds.size)
        fx.clock.advance(StrapLinkLimits.PAIRING_HELP_AFTER_MS - 1_000)
        assertEquals(LinkPhase.PAIRING_HELP, fx.link.snapshot().phase)
        assertEquals(2, fx.ports.bonds.size)
    }

    @Test
    fun stopWhileBondingReturnsToIdleAndCancels() {
        val fx = Fixture()
        fx.bondUntilWaiting()
        fx.link.stop()
        assertEquals(LinkPhase.IDLE, fx.link.snapshot().phase)
        assertTrue(fx.ports.cancels.isNotEmpty())
        fx.link.onBondChanged(ADDRESS, DeviceBond.PRESENT, refused = false)
        fx.clock.advance(StrapLinkLimits.PAIRING_HELP_AFTER_MS)
        assertEquals(LinkPhase.IDLE, fx.link.snapshot().phase)
        assertTrue(fx.session.opened.isEmpty())
        assertEquals(1, fx.ports.bonds.size)
    }

    @Test
    fun alreadyPresentBeginResultSkipsWaiting() {
        val fx = Fixture()
        fx.link.start()
        fx.ports.beginResult = BondBegin.ALREADY_PRESENT
        fx.sight(DeviceBond.ABSENT)
        assertEquals(LinkPhase.CONNECTING, fx.link.snapshot().phase)
        assertEquals(listOf(ADDRESS), fx.session.opened)
        assertEquals(1, fx.ports.bonds.size)
    }

    @Test
    fun refusedBeginResultShowsHelpImmediately() {
        val fx = Fixture()
        fx.link.start()
        fx.ports.beginResult = BondBegin.REFUSED
        fx.sight(DeviceBond.ABSENT)
        assertEquals(LinkPhase.PAIRING_HELP, fx.link.snapshot().phase)
        assertEquals(1, fx.ports.bonds.size)
        assertTrue(fx.session.opened.isEmpty())
    }

    @Test
    fun notStartedCountsTowardTheTwoAttemptCap() {
        val fx = Fixture()
        fx.link.start()
        fx.ports.beginResult = BondBegin.NOT_STARTED
        fx.sight(DeviceBond.ABSENT)
        assertEquals(LinkPhase.FAILED, fx.link.snapshot().phase)
        assertEquals(listOf(ADDRESS, ADDRESS), fx.ports.bonds)
        fx.clock.advance(StrapLinkLimits.PAIRING_HELP_AFTER_MS)
        assertEquals(LinkPhase.FAILED, fx.link.snapshot().phase)
        assertEquals(2, fx.ports.bonds.size)
    }

    @Test
    fun bondEventForAnotherAddressIsIgnored() {
        val fx = Fixture()
        fx.bondUntilWaiting()
        fx.link.onBondChanged("11:22:33:44:55:66", DeviceBond.PRESENT, refused = false)
        assertEquals(LinkPhase.BONDING, fx.link.snapshot().phase)
        assertTrue(fx.session.opened.isEmpty())
    }

    @Test
    fun inProgressBondUpdateDoesNotOpenOrRetry() {
        val fx = Fixture()
        fx.bondUntilWaiting()
        fx.link.onBondChanged(ADDRESS, DeviceBond.BONDING, refused = false)
        assertEquals(LinkPhase.BONDING, fx.link.snapshot().phase)
        assertEquals(1, fx.ports.bonds.size)
        assertTrue(fx.session.opened.isEmpty())
    }

    @Test
    fun sessionReadyMovesConnectingToLinked() {
        val fx = Fixture()
        fx.connectUntilWaiting()
        fx.link.onSessionReady()
        assertEquals(LinkPhase.LINKED, fx.link.snapshot().phase)
        assertEquals(0, fx.link.snapshot().bondAttempt)
        assertEquals(0, fx.link.snapshot().connectAttempt)
        assertTrue(fx.session.open)
    }

    @Test
    fun sessionReadyIgnoredWhenNotConnecting() {
        val fx = Fixture()
        fx.bondUntilWaiting()
        fx.link.onSessionReady()
        assertEquals(LinkPhase.BONDING, fx.link.snapshot().phase)
        assertTrue(fx.session.opened.isEmpty())
    }

    @Test
    fun sessionFailureRetriesOnce() {
        val fx = Fixture()
        fx.connectUntilWaiting()
        fx.session.open = false
        fx.link.onSessionClosed(CloseReason.FAILED, status = 133)
        assertEquals(LinkPhase.CONNECTING, fx.link.snapshot().phase)
        assertEquals(listOf(ADDRESS, ADDRESS), fx.session.opened)
        assertEquals(2, fx.link.snapshot().connectAttempt)
    }

    @Test
    fun secondSessionFailureStops() {
        val fx = Fixture()
        fx.connectUntilWaiting()
        fx.link.onSessionClosed(CloseReason.TIMED_OUT, status = -1)
        fx.link.onSessionClosed(CloseReason.LINK_LOST, status = 8)
        assertEquals(LinkPhase.FAILED, fx.link.snapshot().phase)
        assertEquals(LinkBlock.CONNECT, fx.link.snapshot().block)
        assertEquals(2, fx.session.opened.size)
        fx.link.onSessionClosed(CloseReason.FAILED, status = 133)
        assertEquals(2, fx.session.opened.size)
        assertEquals(LinkPhase.FAILED, fx.link.snapshot().phase)
    }

    @Test
    fun insufficientEncryptionShowsPairingHelpWithoutAnotherOpen() {
        val fx = Fixture()
        fx.connectUntilWaiting()
        fx.link.onSessionClosed(CloseReason.FAILED, SessionFault.INSUFFICIENT_ENCRYPTION)
        assertEquals(LinkPhase.PAIRING_HELP, fx.link.snapshot().phase)
        assertEquals(1, fx.session.opened.size)
        assertFalse(fx.session.open)
        fx.clock.advance(60_000)
        assertEquals(1, fx.session.opened.size)
    }

    @Test
    fun insufficientAuthenticationShowsPairingHelp() {
        val fx = Fixture()
        fx.connectUntilWaiting()
        fx.link.onSessionClosed(CloseReason.FAILED, SessionFault.INSUFFICIENT_AUTHENTICATION)
        assertEquals(LinkPhase.PAIRING_HELP, fx.link.snapshot().phase)
        assertEquals(1, fx.session.opened.size)
    }

    @Test
    fun staleBondWithEncryptionFailureShowsHelp() {
        val fx = Fixture()
        fx.link.start()
        fx.sight(DeviceBond.PRESENT)
        assertEquals(1, fx.session.opened.size)
        fx.link.onSessionClosed(CloseReason.FAILED, SessionFault.INSUFFICIENT_ENCRYPTION)
        assertEquals(LinkPhase.PAIRING_HELP, fx.link.snapshot().phase)
        assertEquals(1, fx.session.opened.size)
        assertTrue(fx.ports.bonds.isEmpty())
    }

    @Test
    fun stopWhileConnectingClosesTheSession() {
        val fx = Fixture()
        fx.connectUntilWaiting()
        fx.link.stop()
        assertEquals(LinkPhase.IDLE, fx.link.snapshot().phase)
        assertFalse(fx.session.open)
        fx.link.onSessionClosed(CloseReason.REQUESTED, status = 0)
        fx.link.onSessionReady()
        assertEquals(LinkPhase.IDLE, fx.link.snapshot().phase)
        assertEquals(1, fx.session.opened.size)
    }

    @Test
    fun requestedCloseWhileConnectingDoesNotRetry() {
        val fx = Fixture()
        fx.connectUntilWaiting()
        fx.link.onSessionClosed(CloseReason.REQUESTED, status = 0)
        assertEquals(LinkPhase.CONNECTING, fx.link.snapshot().phase)
        assertEquals(1, fx.session.opened.size)
    }

    @Test
    fun linkLossReturnsToScanningWithoutANewBondWhenStillBonded() {
        val fx = Fixture()
        fx.connectUntilWaiting()
        fx.link.onSessionReady()
        fx.session.open = false
        fx.link.onSessionClosed(CloseReason.LINK_LOST, status = 8)
        assertEquals(LinkPhase.SCANNING, fx.link.snapshot().phase)
        assertTrue(fx.ports.scanning)
        fx.ports.bond = DeviceBond.PRESENT
        fx.link.onSighting(sighting())
        assertEquals(LinkPhase.CONNECTING, fx.link.snapshot().phase)
        assertEquals(2, fx.session.opened.size)
        assertTrue(fx.ports.bonds.isEmpty())
    }

    @Test
    fun successResetsTheBudgetSoALaterLossCanTryTwice() {
        val fx = Fixture()
        fx.bondUntilWaiting()
        fx.link.onBondChanged(ADDRESS, DeviceBond.ABSENT, refused = false)
        fx.link.onBondChanged(ADDRESS, DeviceBond.PRESENT, refused = false)
        fx.link.onSessionReady()
        assertEquals(LinkPhase.LINKED, fx.link.snapshot().phase)
        assertEquals(2, fx.ports.bonds.size)
        fx.session.open = false
        fx.link.onSessionClosed(CloseReason.LINK_LOST, status = 8)
        fx.ports.bond = DeviceBond.ABSENT
        fx.ports.beginResult = BondBegin.STARTED
        fx.link.onSighting(sighting())
        fx.link.onBondChanged(ADDRESS, DeviceBond.ABSENT, refused = false)
        assertEquals(LinkPhase.BONDING, fx.link.snapshot().phase)
        assertEquals(4, fx.ports.bonds.size)
        fx.link.onBondChanged(ADDRESS, DeviceBond.ABSENT, refused = false)
        assertEquals(LinkPhase.FAILED, fx.link.snapshot().phase)
        assertEquals(4, fx.ports.bonds.size)
    }

    @Test
    fun linkLossDoesNotResetABudgetThatNeverSucceeded() {
        val fx = Fixture()
        fx.connectUntilWaiting()
        fx.link.onSessionClosed(CloseReason.FAILED, status = 133)
        fx.link.onSessionClosed(CloseReason.FAILED, status = 133)
        assertEquals(LinkPhase.FAILED, fx.link.snapshot().phase)
        assertEquals(2, fx.session.opened.size)
    }

    @Test
    fun stopWhileLinkedClosesAndIdles() {
        val fx = Fixture()
        fx.connectUntilWaiting()
        fx.link.onSessionReady()
        fx.link.stop()
        assertEquals(LinkPhase.IDLE, fx.link.snapshot().phase)
        assertFalse(fx.session.open)
        assertFalse(fx.ports.scanning)
        fx.link.onSessionClosed(CloseReason.LINK_LOST, status = 8)
        assertEquals(LinkPhase.IDLE, fx.link.snapshot().phase)
    }

    @Test
    fun deadlineIgnoredOnceConnecting() {
        val fx = Fixture()
        fx.bondUntilWaiting()
        fx.link.onBondChanged(ADDRESS, DeviceBond.PRESENT, refused = false)
        fx.clock.advance(StrapLinkLimits.PAIRING_HELP_AFTER_MS)
        assertEquals(LinkPhase.CONNECTING, fx.link.snapshot().phase)
        assertEquals(1, fx.session.opened.size)
    }

    @Test
    fun retryFromPairingHelpScansAgain() {
        val fx = Fixture()
        fx.bondUntilWaiting()
        fx.link.onBondChanged(ADDRESS, DeviceBond.ABSENT, refused = true)
        fx.link.retry()
        assertEquals(LinkPhase.SCANNING, fx.link.snapshot().phase)
        assertTrue(fx.ports.scanning)
        assertEquals(0, fx.link.snapshot().bondAttempt)
        fx.ports.bond = DeviceBond.PRESENT
        fx.link.onSighting(sighting())
        fx.link.onSessionReady()
        assertEquals(LinkPhase.LINKED, fx.link.snapshot().phase)
        assertEquals(1, fx.ports.bonds.size)
    }

    @Test
    fun retryFromFailedScansAgain() {
        val fx = Fixture()
        fx.link.start()
        fx.link.onScanFailed(ScanFailure.PERMISSION)
        fx.link.retry()
        assertEquals(LinkPhase.SCANNING, fx.link.snapshot().phase)
        assertTrue(fx.ports.scanning)
        assertEquals(null, fx.link.snapshot().scanFailure)
    }

    @Test
    fun retryWhileBondingDoesNothing() {
        val fx = Fixture()
        fx.bondUntilWaiting()
        fx.link.retry()
        assertEquals(LinkPhase.BONDING, fx.link.snapshot().phase)
        assertEquals(1, fx.ports.bonds.size)
        assertFalse(fx.ports.scanning)
    }

    @Test
    fun stopFromPairingHelpIdles() {
        val fx = Fixture()
        fx.bondUntilWaiting()
        fx.link.onBondChanged(ADDRESS, DeviceBond.ABSENT, refused = true)
        fx.link.stop()
        assertEquals(LinkPhase.IDLE, fx.link.snapshot().phase)
        fx.link.onSighting(sighting())
        assertTrue(fx.session.opened.isEmpty())
    }

    @Test
    fun stopFromFailedIdles() {
        val fx = Fixture()
        fx.link.start()
        fx.link.onScanFailed(1)
        fx.link.stop()
        assertEquals(LinkPhase.IDLE, fx.link.snapshot().phase)
        assertEquals(LinkBlock.NONE, fx.link.snapshot().block)
    }

    @Test
    fun startWhileScanningDoesNotStartASecondScan() {
        val fx = Fixture()
        fx.link.start()
        fx.link.start()
        assertEquals(1, fx.ports.scanStarts)
        assertEquals(LinkPhase.SCANNING, fx.link.snapshot().phase)
    }

    @Test
    fun stopWhileIdleIsANoOp() {
        val fx = Fixture()
        fx.link.stop()
        assertEquals(listOf(LinkPhase.IDLE), fx.phases)
        assertEquals(0, fx.ports.scanStarts)
    }

    @Test
    fun pairingHelpDoesNotFollowASuccessfulBondThatDrops() {
        val fx = Fixture()
        fx.connectUntilWaiting()
        fx.link.onSessionReady()
        fx.session.open = false
        fx.link.onSessionClosed(CloseReason.LINK_LOST, status = 8)
        assertEquals(LinkPhase.SCANNING, fx.link.snapshot().phase)
        assertEquals(LinkBlock.NONE, fx.link.snapshot().block)
    }
}

private class Fixture {
    val session = FakeSession()
    val ports = FakeLinkPorts(session)
    val clock = ManualLinkClock()
    val link = StrapLink(ports, clock)
    val phases = mutableListOf<LinkPhase>()

    init {
        link.watch { phases += it.phase }
    }

    fun sight(bond: DeviceBond) {
        ports.bond = bond
        link.onSighting(sighting())
    }

    fun bondUntilWaiting() {
        link.start()
        sight(DeviceBond.ABSENT)
        assertEquals(LinkPhase.BONDING, link.snapshot().phase)
    }

    fun connectUntilWaiting() {
        link.start()
        sight(DeviceBond.PRESENT)
        assertEquals(LinkPhase.CONNECTING, link.snapshot().phase)
    }
}

private class FakeSession {
    val opened = mutableListOf<String>()
    var open: Boolean = false

    fun open(address: String) {
        open = true
        opened += address
    }

    fun close() {
        open = false
    }
}

private class FakeLinkPorts(
    private val session: FakeSession,
) : LinkPorts {
    var scanning = false
    var scanStarts = 0
    var bond = DeviceBond.ABSENT
    var beginResult = BondBegin.STARTED
    val bonds = mutableListOf<String>()
    val cancels = mutableListOf<String>()
    val heartRateWatches = mutableListOf<String>()

    override fun startScan() {
        scanning = true
        scanStarts += 1
    }

    override fun stopScan() {
        scanning = false
    }

    override fun bondOf(address: String): DeviceBond = bond

    override fun beginBond(address: String): BondBegin {
        bonds += address
        return beginResult
    }

    override fun cancelBond() {
        cancels += addressOrBlank()
    }

    override fun watchHeartRate(address: String) {
        heartRateWatches += address
    }

    override fun openSession(address: String) {
        session.open(address)
    }

    override fun closeSession() {
        session.close()
    }

    private fun addressOrBlank(): String = bonds.lastOrNull() ?: ""
}

private class ManualLinkClock : LinkClock {
    private var now = 0L
    private val tasks = mutableListOf<Scheduled>()

    private class Scheduled(val due: Long, val task: () -> Unit) {
        var cancelled = false
    }

    override fun after(delayMs: Long, task: () -> Unit): LinkAlarm {
        val scheduled = Scheduled(now + delayMs, task)
        tasks += scheduled
        return LinkAlarm { scheduled.cancelled = true }
    }

    fun advance(ms: Long) {
        now += ms
        val due = tasks.filter { !it.cancelled && it.due <= now }
        tasks.removeAll(due.toSet())
        due.forEach { it.task() }
    }
}

private fun sighting(address: String = ADDRESS) = StrapSighting(address, rssi = -40, advertisedName = null)

private const val ADDRESS: String = "AA:BB:CC:DD:EE:FF"
