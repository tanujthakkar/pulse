package app.pulse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class PulseRouteTest {
    @Test
    fun theShellKeepsHomeAndSettingsOnly() {
        assertEquals(
            listOf("home", "more", "connect", "debug"),
            PulseRoute.entries.map { it.route },
        )
        assertFalse(PulseRoute.entries.map { it.route }.contains(IMPORT_ROUTE))
        assertFalse(PulseRoute.entries.map { it.route }.contains("days"))
        assertFalse(PulseRoute.entries.map { it.route }.contains("sync"))
        assertFalse(PulseRoute.entries.map { it.route }.contains("live"))
        assertEquals(PulseRoute.Home, PulseRoute.entries.first())
    }
}
