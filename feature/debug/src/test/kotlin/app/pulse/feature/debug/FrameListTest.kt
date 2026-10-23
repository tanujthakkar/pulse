package app.pulse.feature.debug

import app.pulse.wire.codec.CommandResult
import app.pulse.wire.codec.FrameCodec
import app.pulse.wire.codec.FrameIntake
import app.pulse.wire.codec.FrameNote
import app.pulse.wire.codec.FrameRing
import app.pulse.wire.codec.FrameVerdict
import app.pulse.wire.codec.HelloBody
import app.pulse.wire.codec.HelloCommand
import app.pulse.wire.codec.PacketType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameListTest {
    @Test
    fun filterKeepsNewestOrderAndCatalogListsTypes() {
        val notes = listOf(
            note(id = 3, type = null, marker = "—"),
            note(id = 2, type = PacketType.COMMAND_RESPONSE, marker = "opcode 145"),
            note(id = 1, type = PacketType.COMMAND, marker = "opcode 26"),
        )
        assertEquals(notes, selectFrames(notes, FrameFilter()))
        assertEquals(listOf(notes[1]), selectFrames(notes, FrameFilter(packetType = PacketType.COMMAND_RESPONSE)))
        assertEquals(listOf(notes[0]), selectFrames(notes, FrameFilter(unframedOnly = true)))
        assertEquals(FrameCatalog(types = listOf(35, 36), hasUnframed = true), frameCatalog(notes))
    }

    @Test
    fun exportTextListsFieldsAndRedactsHelloIdentity() {
        val frame = helloResponse()
        val ring = FrameRing()
        FrameIntake(ring).offer(
            incoming = true,
            characteristic = "fd4b0003",
            framed = true,
            payload = frame,
            atMillis = 0L,
        )
        val notes = ring.snapshot()
        val line = frameLine(notes.single())
        assertTrue(line.contains("1970-01-01T00:00:00Z"))
        assertTrue(line.contains("in fd4b0003"))
        assertTrue(line.contains("type=36"))
        assertTrue(line.contains("opcode 145"))
        assertTrue(line.contains("size=${frame.size}"))
        assertTrue(line.contains("crc=${FrameVerdict.OK}"))
        val text = frameLogText(notes)
        assertTrue(text.startsWith("pulse frames 1\n"))
        assertTrue(text.contains("«redacted»"))
        assertFalse(text.contains("SECRETBLOCK"))
        assertIdentityHidden(frame, text)
        val out = ByteArrayOutputStream()
        assertEquals("Exported 1 frames.", exportFrames({ out }, notes))
        assertEquals(text, out.toString(Charsets.UTF_8))
    }

    @Test
    fun exportFailureStaysLocal() {
        assertEquals("Export failed.", exportFrames({ null }, emptyList()))
        val broken = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("disk")
            }
        }
        assertEquals("Export failed.", exportFrames({ broken }, emptyList()))
        assertEquals("pulse frames 0\n", frameLogText(emptyList()))
    }

    private fun note(id: Long, type: Int?, marker: String) = FrameNote(
        id = id,
        atMillis = id,
        incoming = true,
        characteristic = "fd4b0003",
        packetType = type,
        marker = marker,
        size = 16,
        crc = FrameVerdict.OK,
        hex = "aa",
    )
}

private fun assertIdentityHidden(frame: ByteArray, text: String) {
    val hidden = HelloBody.IDENTITY.flatMap { range -> range.map { 13 + it } }.toSet()
    val outside = frame.indices.filter { it !in hidden }.map { frame[it].toInt() and 0xFF }.toSet()
    val exclusive = hidden.map { frame[it].toInt() and 0xFF }.toSet() - outside
    assertTrue(exclusive.isNotEmpty())
    val tokens = text.split(' ', '\n')
    exclusive.forEach { value ->
        assertFalse(tokens.any { it == "%02x".format(value) })
    }
}

private fun helloResponse(): ByteArray {
    val body = ByteArray(HelloBody.FINAL_LENGTH)
    body[0] = HelloCommand.REVISION.toByte()
    val word = "SECRETBLOCK".toByteArray(Charsets.US_ASCII)
    for (range in HelloBody.IDENTITY) {
        for (index in range) body[index] = word[(index - range.first) % word.size]
    }
    body[HelloBody.VERSION_OFFSET] = 50
    body[HelloBody.VERSION_OFFSET + 1] = 42
    body[HelloBody.VERSION_OFFSET + 2] = 1
    return FrameCodec.encodeCommandResponse(
        sequence = 2,
        command = HelloCommand.NUMBER,
        originSequence = 1,
        result = CommandResult.SUCCESS,
        body = body,
    )
}
