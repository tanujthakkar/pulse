package app.pulse.feature.debug

import app.pulse.wire.codec.FrameNote
import java.io.OutputStream
import java.time.Instant

/**
 * Which notes the debug list shows.
 *
 * [unframedOnly] lists notes that are not format-1 frames. Otherwise
 * [packetType] null lists every note, and a number lists that packet type.
 */
data class FrameFilter(
    val packetType: Int? = null,
    val unframedOnly: Boolean = false,
)

data class FrameCatalog(
    val types: List<Int> = emptyList(),
    val hasUnframed: Boolean = false,
)

fun selectFrames(notes: List<FrameNote>, filter: FrameFilter): List<FrameNote> = when {
    filter.unframedOnly -> notes.filter { it.packetType == null }
    filter.packetType != null -> notes.filter { it.packetType == filter.packetType }
    else -> notes
}

fun frameCatalog(notes: List<FrameNote>): FrameCatalog = FrameCatalog(
    types = notes.mapNotNull { it.packetType }.distinct().sorted(),
    hasUnframed = notes.any { it.packetType == null },
)

fun frameLine(note: FrameNote): String {
    val direction = if (note.incoming) "in" else "out"
    val type = note.packetType?.toString() ?: "—"
    val time = Instant.ofEpochMilli(note.atMillis).toString()
    return "$time $direction ${note.characteristic} type=$type ${note.marker} size=${note.size} crc=${note.crc}"
}

/** Newest-first text of the ring. [FrameNote.hex] is already redacted. */
fun frameLogText(notes: List<FrameNote>): String = buildString {
    append("pulse frames ")
    append(notes.size)
    append('\n')
    for (note in notes) {
        append(frameLine(note))
        append('\n')
        append(note.hex)
        append("\n\n")
    }
}

/**
 * Writes [notes] to a local stream the caller opened.
 *
 * The Storage Access Framework supplies that stream. This function does not
 * open a network connection. A missing stream or an I/O failure returns
 * "Export failed."
 */
fun exportFrames(open: () -> OutputStream?, notes: List<FrameNote>): String {
    return try {
        val stream = open() ?: return "Export failed."
        stream.use { it.write(frameLogText(notes).toByteArray(Charsets.UTF_8)) }
        "Exported ${notes.size} frames."
    } catch (_: Exception) {
        "Export failed."
    }
}
