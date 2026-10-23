package app.pulse.link

import app.pulse.ble.BondBegin
import app.pulse.ble.CloseReason
import app.pulse.ble.DeviceBond
import app.pulse.ble.StrapSighting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinkForegroundTest {
    @Test
    fun serviceRunsFromTheFirstSearchUntilACleanStop() {
        assertFalse(LinkForegroundPolicy.shouldRun(LinkPhase.IDLE))
        assertTrue(LinkForegroundPolicy.shouldRun(LinkPhase.SCANNING))
        assertTrue(LinkForegroundPolicy.shouldRun(LinkPhase.BONDING))
        assertTrue(LinkForegroundPolicy.shouldRun(LinkPhase.CONNECTING))
        assertTrue(LinkForegroundPolicy.shouldRun(LinkPhase.LINKED))
        assertFalse(LinkForegroundPolicy.shouldRun(LinkPhase.PAIRING_HELP))
        assertFalse(LinkForegroundPolicy.shouldRun(LinkPhase.FAILED))
    }

    @Test
    fun notificationCopyNamesPulseAndTheConnection() {
        val linked = LinkSnapshot(
            phase = LinkPhase.LINKED,
            hello = HelloProgress.SUCCEEDED,
            serviceBatteryPercent = 80,
        )
        assertEquals("Strap connected · 80% battery", linkStatusLine(linked, syncing = false))
        assertEquals(
            "Syncing strap history · 80% battery",
            linkStatusLine(linked, syncing = true),
        )
        assertEquals(
            "Strap connected · setup incomplete",
            linkStatusLine(
                linked.copy(hello = HelloProgress.FAILED, serviceBatteryPercent = null),
                syncing = false,
            ),
        )
        assertEquals(
            "Strap connected · 61% battery",
            linkStatusLine(
                LinkSnapshot(phase = LinkPhase.LINKED, commandBatteryPercent = 61),
                syncing = false,
            ),
        )
        assertEquals(
            "Looking for a strap",
            linkStatusLine(LinkSnapshot(phase = LinkPhase.SCANNING), syncing = false),
        )
        val copy = linkStatusLine(linked, syncing = true)
        assertFalse(copy.contains("WHOOP"))
        assertFalse(copy.contains("NOOP"))
        assertFalse(copy.contains("OpenStrap"))
        assertTrue(LinkNotifications.TITLE == "Pulse")
    }

    @Test
    fun hostStartsOnceAndStopsWhenTheUserDisconnects() {
        val link = StrapLink(presentPorts(), idleClock())
        var starts = 0
        var stops = 0
        val host = host(link, onStart = { starts += 1 }, onStop = { stops += 1 })
        assertFalse(host.holding)
        assertEquals(0, starts)

        link.start()
        assertTrue(host.holding)
        assertEquals(1, starts)

        link.onSighting(sighting())
        link.onSessionReady()
        assertEquals(LinkPhase.LINKED, link.snapshot().phase)
        assertEquals(1, starts)
        assertEquals(0, stops)

        link.stop()
        assertEquals(LinkPhase.IDLE, link.snapshot().phase)
        assertFalse(host.holding)
        assertEquals(1, stops)
    }

    @Test
    fun unexpectedDropKeepsTheServiceUntilTheUserDisconnects() {
        val link = StrapLink(presentPorts(), idleClock())
        var starts = 0
        var stops = 0
        host(link, onStart = { starts += 1 }, onStop = { stops += 1 })
        link.start()
        link.onSighting(sighting())
        link.onSessionReady()
        link.onSessionClosed(CloseReason.LINK_LOST, status = 0)
        assertEquals(LinkPhase.SCANNING, link.snapshot().phase)
        assertEquals(1, starts)
        assertEquals(0, stops)

        link.stop()
        assertEquals(1, stops)
    }

    @Test
    fun pairingHelpAndARejectedStartReleaseTheService() {
        val refused = StrapLink(
            presentPorts(bond = DeviceBond.ABSENT, begin = BondBegin.REFUSED),
            idleClock(),
        )
        var stops = 0
        val refusedHost = host(refused, onStart = {}, onStop = { stops += 1 })
        refused.start()
        refused.onSighting(sighting())
        assertEquals(LinkPhase.PAIRING_HELP, refused.snapshot().phase)
        assertEquals(1, stops)
        assertFalse(refusedHost.holding)

        val blocked = StrapLink(presentPorts(), idleClock())
        val blockedHost = host(
            blocked,
            onStart = { throw IllegalStateException("background") },
            onStop = {},
        )
        blocked.start()
        assertEquals(LinkPhase.SCANNING, blocked.snapshot().phase)
        assertFalse(blockedHost.holding)
    }

    private fun host(
        link: StrapLink,
        onStart: () -> Unit,
        onStop: () -> Unit,
    ): ConnectedLinkHost = ConnectedLinkHost(link, onStart, onStop, log = {})

    private fun presentPorts(
        bond: DeviceBond = DeviceBond.PRESENT,
        begin: BondBegin = BondBegin.STARTED,
    ): LinkPorts = object : LinkPorts {
        override fun startScan() = Unit

        override fun stopScan() = Unit

        override fun bondOf(address: String): DeviceBond = bond

        override fun beginBond(address: String): BondBegin = begin

        override fun cancelBond() = Unit

        override fun openSession(address: String) = Unit

        override fun closeSession() = Unit

        override fun watchHeartRate(address: String) = Unit
    }

    private fun idleClock(): LinkClock = object : LinkClock {
        override fun after(delayMs: Long, task: () -> Unit): LinkAlarm = LinkAlarm { }
    }

    private fun sighting() = StrapSighting(ADDRESS, rssi = -40, advertisedName = null)

    private companion object {
        const val ADDRESS: String = "AA:BB:CC:DD:EE:FF"
    }
}
