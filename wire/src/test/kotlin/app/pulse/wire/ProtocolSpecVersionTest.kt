package app.pulse.wire

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProtocolSpecVersionTest {
    @Test
    fun skeletonStaysOnSpecVersionZero() {
        assertEquals("0", ProtocolSpecVersion.CURRENT)
    }
}
