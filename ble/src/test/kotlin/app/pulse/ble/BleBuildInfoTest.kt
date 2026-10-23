package app.pulse.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BleBuildInfoTest {
    @Test
    fun tracksTheSpecVersion() {
        assertEquals("0", BleBuildInfo.SPEC)
    }
}
