package app.pulse.testing.golden

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HelloFrameFixtureTest {
    @Test
    fun locksThePublishedHelloBytes() {
        val text = resource("/frames/hello-command.hex")
        val compact = text.trim().split(Regex("\\s+")).joinToString("")
        assertEquals("AA0108000001E67123019101363E5C8D", compact)
    }

    @Test
    fun helloFixtureRecordsProvenance() {
        val text = resource("/frames/hello-command.provenance.txt")
        assertTrue(text.contains("PROTOCOL_WHOOP5.md#connection-and-frame-format"))
        assertTrue(text.contains("firmware: 50.42.1.0"))
        assertTrue(text.contains("confidence: documented"))
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { path }.bufferedReader().readText()
}
