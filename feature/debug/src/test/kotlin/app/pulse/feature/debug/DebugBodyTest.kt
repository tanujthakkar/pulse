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
    }
}