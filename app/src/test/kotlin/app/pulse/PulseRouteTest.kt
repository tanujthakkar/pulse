package app.pulse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class PulseRouteTest {
    @Test
    fun theBarStartsAtHomeAndKeepsTheOtherScreens() {
        assertEquals(
            listOf("home", "days", "sync", "live", "more", "connect", "debug"),
            PulseRoute.entries.map { it.route },
        )
        assertEquals(listOf("home", "days", "sync", "live", "more"), PulseRoute.entries.filter { it.inBar }.map { it.route })
        assertEquals("Dy", PulseRoute.Days.mark)
        assertEquals("D", PulseRoute.Debug.mark)
        assertEquals(PulseRoute.Home.route, PulseRoute.entries.first { it.inBar }.route)
        assertFalse(PulseRoute.entries.map { it.route }.contains(IMPORT_ROUTE))
        assertFalse(PulseRoute.Debug.inBar)
        assertFalse(PulseRoute.Connect.inBar)
    }
}
