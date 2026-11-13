package app.pulse.link

import app.pulse.sync.SyncActivity
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

    @Test
    fun aResyncShowsChunksThatWereAlreadyStored() {
        val activity = SyncActivity(running = true, chunks = 0, records = 366_300, endsReceived = 12)
        assertEquals(12, syncingChunkCount(activity))
        assertEquals(
            StrapPresence(connected = true, syncing = true, chunks = 12),
            strapPresence(
                LinkSnapshot(phase = LinkPhase.LINKED),
                running = true,
                chunks = syncingChunkCount(activity),
                charging = null,
            ),
        )
    }
}
