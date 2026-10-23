package app.pulse.testing.golden

import app.pulse.wire.codec.HelloCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CaptureSlotTest {
    @Test
    fun helloCommandFixtureStillMatchesTheCodec() {
        val text = resource("/frames/hello-command.hex")
        val expected = text.trim().split(Regex("\\s+")).joinToString("")
        val actual = HelloCommand.bytes().joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        assertEquals(expected, actual)
    }

    @Test
    fun captureSlotsAreDocumentedAndEmpty() {
        val note = resource("/frames/slots/README.txt")
        assertTrue(note.contains("captured-hello-response.hex"))
        assertTrue(note.contains("captured-metadata.hex"))
        assertTrue(note.contains("14..24"))
        assertTrue(note.contains("21..28"))
        assertNull(javaClass.getResource("/frames/slots/captured-hello-response.hex"))
        assertNull(javaClass.getResource("/frames/slots/captured-metadata.hex"))
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { path }.bufferedReader().readText()
}