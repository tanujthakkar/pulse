package app.pulse.feature.home

import app.pulse.link.StrapPresence
import org.junit.Assert.assertEquals
import org.junit.Test

class StrapStatusLabelTest {
    @Test
    fun labelsNameTheFourStates() {
        assertEquals("Strap connected", strapStatusLabel(StrapPresence(connected = true)))
        assertEquals("Strap disconnected", strapStatusLabel(StrapPresence()))
        assertEquals(
            "Strap connected, charging, 64 percent",
            strapStatusLabel(StrapPresence(connected = true, charging = true, batteryPercent = 64)),
        )
        assertEquals(
            "Strap connected, syncing, 12 chunks",
            strapStatusLabel(StrapPresence(connected = true, syncing = true, chunks = 12)),
        )
    }
}
