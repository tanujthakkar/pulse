package app.pulse.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LinkBuildInfoTest {
    @Test
    fun tracksTheSpecVersion() {
        assertEquals("0", LinkBuildInfo.SPEC)
        assertEquals(LinkBuildInfo.SPEC, LinkBuildInfo.TRANSPORT_SPEC)
    }
}
