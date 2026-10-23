package app.pulse.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HistorySyncEngineTest {
    @Test
    fun skeletonEngineCarriesTheSpecVersionOnly() {
        assertEquals("0", HistorySyncEngine().specVersion)
    }
}
