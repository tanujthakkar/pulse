package app.pulse.link

import org.junit.Assert.assertEquals
import org.junit.Test

class StrapPresenceTest {
    @Test
    fun chargingNeedsTheEventAndALink() {
        val linked = LinkSnapshot(
            phase = LinkPhase.LINKED,
            serviceBatteryPercent = 64,
        )
        assertEquals(
            StrapPresence(connected = true, batteryPercent = 64),
            strapPresence(linked, running = false, chunks = 0, charging = null),
        )
        assertEquals(
            StrapPresence(connected = true, charging = true, batteryPercent = 64),
            strapPresence(linked, running = false, chunks = 0, charging = true),
        )
        assertEquals(
            StrapPresence(connected = false, batteryPercent = 64),
            strapPresence(linked.copy(phase = LinkPhase.IDLE), running = false, chunks = 0, charging = true),
        )
        val commandOnly = LinkSnapshot(phase = LinkPhase.LINKED, commandBatteryPercent = 64L)
        assertEquals(
            StrapPresence(connected = true, charging = true, batteryPercent = 64, syncing = true, chunks = 3),
            strapPresence(commandOnly, running = true, chunks = 3, charging = true),
        )
    }
}
