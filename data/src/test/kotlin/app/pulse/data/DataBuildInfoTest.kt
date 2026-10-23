package app.pulse.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DataBuildInfoTest {
    @Test
    fun tracksTheSpecVersion() {
        assertEquals("0", DataBuildInfo.SPEC)
    }
}
