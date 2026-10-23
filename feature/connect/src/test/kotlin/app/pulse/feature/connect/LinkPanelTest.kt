package app.pulse.feature.connect

import app.pulse.link.HelloProgress
import app.pulse.link.LinkBlock
import app.pulse.link.LinkPhase
import app.pulse.link.LinkSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPanelTest {
    @Test
    fun everyPhaseHasItsOwnStatus() {
        val statuses = LinkPhase.entries.map { phase ->
            LinkPanel.from(LinkSnapshot(phase = phase, bondAttempt = 1, connectAttempt = 1)).status
        }
        assertEquals(LinkPhase.entries.size, statuses.toSet().size)
        statuses.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun pairingHelpExplainsTheOtherBondAndDoesNotAutoRetry() {
        val panel = LinkPanel.from(
            LinkSnapshot(
                phase = LinkPhase.PAIRING_HELP,
                address = "AA:BB:CC:DD:EE:FF",
                block = LinkBlock.BOND,
                bondAttempt = 1,
            ),
        )
        assertTrue(panel.pairingHelp)
        assertEquals("The strap is still bonded to another phone.", panel.status)
        assertEquals(LinkPanel.HELP_TITLE, panel.helpTitle)
        assertTrue(panel.helpBody.contains("one encrypted bond"))
        assertTrue(panel.helpBody.contains("flash blue"))
        assertTrue(panel.helpBody.contains("firmly"))
        assertTrue(panel.helpBody.contains("official app"))
        assertTrue(panel.helpBody.contains("at most twice"))
        assertEquals("AA:BB:CC:DD:EE:FF", panel.address)
        assertFalse(panel.allowRetry)
        assertFalse(panel.showStop)
    }

    @Test
    fun failedScanOffersRetryWithoutPairingHelp() {
        val off = LinkPanel.from(
            LinkSnapshot(
                phase = LinkPhase.FAILED,
                block = LinkBlock.SCAN,
                bluetoothOff = true,
            ),
        )
        assertEquals("Bluetooth is off.", off.status)
        assertTrue(off.allowRetry)
        assertFalse(off.pairingHelp)

        val other = LinkPanel.from(
            LinkSnapshot(phase = LinkPhase.FAILED, block = LinkBlock.SCAN, scanFailure = 1),
        )
        assertEquals("The scan stopped before a strap was found.", other.status)
    }

    @Test
    fun linkedHelloShowsBothFirmwareSourcesAndBothBatteries() {
        val match = LinkPanel.from(
            LinkSnapshot(
                phase = LinkPhase.LINKED,
                hello = HelloProgress.SUCCEEDED,
                helloFirmware = "50.42.1.0",
                informationFirmware = "50.42.1.0",
                commandBatteryPercent = 80,
                serviceBatteryPercent = 81,
            ),
        )
        assertEquals("Hello succeeded.", match.handshake)
        assertEquals("Firmware (hello): 50.42.1.0", match.helloFirmware)
        assertEquals("Firmware (device information): 50.42.1.0", match.informationFirmware)
        assertEquals("Firmware strings match.", match.firmwareNote)
        assertEquals("Battery (command 26): 80%", match.commandBattery)
        assertEquals("Battery (standard battery service): 81%", match.serviceBattery)
        assertFalse(match.offerRepair)

        val differ = LinkPanel.from(
            LinkSnapshot(
                phase = LinkPhase.LINKED,
                hello = HelloProgress.FAILED,
                offerRepair = true,
                heartRateLive = true,
                helloFirmware = "50.42.1.0",
                informationFirmware = "50.38.1.0",
            ),
        )
        assertEquals("Firmware strings differ.", differ.firmwareNote)
        assertEquals("Hello failed. Live heart rate is still on.", differ.handshake)
        assertTrue(differ.offerRepair)
        assertTrue(differ.showStop)
    }

    @Test
    fun activePhasesCanBeStopped() {
        listOf(LinkPhase.SCANNING, LinkPhase.BONDING, LinkPhase.CONNECTING, LinkPhase.LINKED).forEach { phase ->
            assertTrue(phase.name, LinkPanel.from(LinkSnapshot(phase = phase)).showStop)
        }
        assertFalse(LinkPanel.from(LinkSnapshot(phase = LinkPhase.IDLE)).showStop)
    }
}
