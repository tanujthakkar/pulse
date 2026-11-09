package app.pulse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class PulseRouteTest {
    @Test
    fun shellHasConnectLiveSyncDaysAndDebug() {
        assertEquals(
            listOf("connect", "live", "sync", "days", "debug"),
            PulseRoute.entries.map { it.route },
        )
        assertEquals("Dy", PulseRoute.Days.mark)
        assertEquals("D", PulseRoute.Debug.mark)
        assertFalse(PulseRoute.entries.map { it.route }.contains(IMPORT_ROUTE))
    }
}
