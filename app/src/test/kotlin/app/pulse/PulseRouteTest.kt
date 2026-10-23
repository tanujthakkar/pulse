package app.pulse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PulseRouteTest {
    @Test
    fun shellHasTheFourSkeletonScreens() {
        assertEquals(
            listOf("connect", "live", "sync", "debug"),
            PulseRoute.entries.map { it.route },
        )
    }
}
