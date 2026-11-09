package app.pulse.feature.debug

import app.pulse.data.DeviceRow
import app.pulse.link.HelloProgress
import app.pulse.link.LinkSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugBodyTest {
    @Test
    fun debugTextShowsSourcesAndDoesNotCarryIdentity() {
        val body = debugBody(
            LinkSnapshot(
                hello = HelloProgress.SUCCEEDED,
                helloFirmware = "50.42.1.0",
                informationFirmware = "50.42.1.0",
                commandBatteryPercent = 80,
                serviceBatteryPercent = 81,
            ),
            DeviceRow(
                address = "AA:BB:CC:DD:EE:FF",
                helloFirmware = "50.42.1.0",
                informationFirmware = "50.42.1.0",
                commandBatteryPercent = 80,
                serviceBatteryPercent = 81,
                updatedAtMillis = 1,
            ),
        )
        assertTrue(body.contains("Identity blocks from hello are redacted and are not stored."))
        assertTrue(body.contains("Firmware (hello): 50.42.1.0"))
        assertTrue(body.contains("Firmware (device information): 50.42.1.0"))
        assertTrue(body.contains("Firmware strings match."))
        assertTrue(body.contains("Battery (command 26): 80%"))
        assertTrue(body.contains("Battery (standard battery service): 81%"))
        assertTrue(body.contains("Stored device AA:BB:CC:DD:EE:FF."))
        assertFalse(body.contains("SECRET"))
        assertFalse(body.contains("identity block"))
        val exported = debugBody(
            LinkSnapshot(helloFirmware = "50.43.1.0"),
            null,
            "Strap result 1: 1 chunks. GATT status 0 is a radio result. It is not a strap result.",
        )
        assertTrue(exported.contains("Strap result 1: 1"))
        assertTrue(exported.contains("not a strap result"))
        assertFalse(exported.contains("GATT status 0 is success"))
        assertTrue(exported.contains("Day metrics are at the top of a frame export."))
    }

    @Test
    fun recordingStatusSaysWhetherNewFramesAreStored() {
        val running = recordingStatus(paused = false, stored = 4)
        val paused = recordingStatus(paused = true, stored = 4)
        assertTrue(running.contains("Recording is running."))
        assertTrue(running.contains("New frames are stored."))
        assertTrue(running.contains("4 stored."))
        assertTrue(paused.contains("Recording is paused."))
        assertTrue(paused.contains("can miss traffic"))
        assertTrue(paused.contains("4 stored."))
        assertFalse(paused.contains("Recording is running."))
        val full = recordingStatus(paused = false, stored = 40_000, capacity = 40_000)
        assertTrue(full.contains("The ring is full, so older frames were dropped."))
    }
}