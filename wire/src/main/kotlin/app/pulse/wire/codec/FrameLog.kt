package app.pulse.wire.codec

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * One captured payload for the debug screen.
 *
 * [hex] is the only copy that is kept. Hello identity blocks are replaced
 * with [HelloRedaction.MARKER] before the note is stored. Spec §7 and
 * project rule 19.
 */
data class FrameNote(
    val id: Long,
    val atMillis: Long,
    val incoming: Boolean,
    val characteristic: String,
    val packetType: Int?,
    val marker: String,
    val size: Int,
    val crc: String,
    val hex: String,
)

object FrameVerdict {
    const val OK: String = "ok"
    const val NOT_A_FRAME: String = "not a frame"
}

/**
 * Newest-first ring of [FrameNote].
 *
 * The debug screen keeps about the last [CAPACITY] frames. [setPaused] drops
 * new notes until recording resumes. Bytes are not retained here.
 */
class FrameRing(val capacity: Int = CAPACITY) {
    init {
        require(capacity >= 1) { "frame ring capacity must be positive" }
    }

    private val lock = Any()
    private val items = ArrayDeque<FrameNote>()
    private val ids = AtomicLong()
    private val watchers = ArrayList<() -> Unit>()

    @Volatile
    var paused: Boolean = false
        private set

    fun nextId(): Long = ids.incrementAndGet()

    fun setPaused(value: Boolean) {
        paused = value
    }

    fun add(note: FrameNote) {
        if (paused) return
        val listeners = synchronized(lock) {
            items.addFirst(note)
            while (items.size > capacity) items.removeLast()
            watchers.toList()
        }
        listeners.forEach { it() }
    }

    fun snapshot(): List<FrameNote> = synchronized(lock) { items.toList() }

    fun size(): Int = synchronized(lock) { items.size }

    /** [listener] runs after a note is stored. The returned function removes it. */
    fun watch(listener: () -> Unit): () -> Unit {
        synchronized(lock) { watchers.add(listener) }
        return { synchronized(lock) { watchers.remove(listener) } }
    }

    companion object {
        const val CAPACITY: Int = 5_000
    }
}

/**
 * Turns GATT payloads into [FrameNote]s.
 *
 * Inbound format-1 characteristics are reassembled with [FrameAccumulator].
 * Writes are one frame each. This type never transmits and never reads
 * [HistoryAckGate]. Each session should use its own intake so a partial
 * frame cannot continue on the next connection. [ring] is shared.
 */
class FrameIntake(val ring: FrameRing) {
    private val lock = Any()
    private val incoming = HashMap<String, FrameAccumulator>()

    fun offer(
        incoming: Boolean,
        characteristic: String,
        framed: Boolean,
        payload: ByteArray,
        atMillis: Long,
    ) {
        if (payload.isEmpty()) return
        val notes = synchronized(lock) {
            if (incoming && framed) {
                fromStream(characteristic, payload, atMillis)
            } else {
                listOf(single(incoming, characteristic, payload, atMillis))
            }
        }
        notes.forEach { ring.add(it) }
    }

    private fun fromStream(characteristic: String, payload: ByteArray, atMillis: Long): List<FrameNote> {
        val accumulator = incoming.getOrPut(characteristic) { FrameAccumulator() }
        val notes = ArrayList<FrameNote>()
        for (event in accumulator.append(payload)) {
            when (event) {
                is AccumulatorEvent.Frame -> notes += accepted(event.frame, characteristic, atMillis)
                is AccumulatorEvent.Rejected -> {
                    val sample = event.bytes ?: continue
                    if (event.reason == RejectReason.BAD_START) continue
                    notes += rejected(sample, event.reason, characteristic, atMillis)
                }
            }
        }
        return notes
    }

    private fun single(
        incoming: Boolean,
        characteristic: String,
        payload: ByteArray,
        atMillis: Long,
    ): FrameNote {
        if (incoming) {
            return note(
                incoming = true,
                characteristic = characteristic,
                bytes = payload,
                atMillis = atMillis,
                packetType = null,
                marker = "—",
                crc = FrameVerdict.NOT_A_FRAME,
            )
        }
        return when (val decoded = FrameCodec.decode(payload)) {
            is DecodeResult.Ok -> accepted(decoded.frame, characteristic, atMillis, incoming = false)
            is DecodeResult.Rejected -> rejected(payload, decoded.reason, characteristic, atMillis, incoming = false)
        }
    }

    private fun accepted(
        frame: WireFrame,
        characteristic: String,
        atMillis: Long,
        incoming: Boolean = true,
    ): FrameNote = note(
        incoming = incoming,
        characteristic = characteristic,
        bytes = frame.bytes,
        atMillis = atMillis,
        packetType = frame.packetType,
        marker = markerLabel(frame.packetType, frame.sequence, frame.command),
        crc = FrameVerdict.OK,
    )

    private fun rejected(
        bytes: ByteArray,
        reason: RejectReason,
        characteristic: String,
        atMillis: Long,
        incoming: Boolean = true,
    ): FrameNote {
        val type = packetTypeOf(bytes)
        val marker = if (type == null) {
            "—"
        } else {
            markerLabel(type, bytes[9].toInt() and 0xFF, bytes[10].toInt() and 0xFF)
        }
        return note(
            incoming = incoming,
            characteristic = characteristic,
            bytes = bytes,
            atMillis = atMillis,
            packetType = type,
            marker = marker,
            crc = crcLabel(reason),
        )
    }

    private fun note(
        incoming: Boolean,
        characteristic: String,
        bytes: ByteArray,
        atMillis: Long,
        packetType: Int?,
        marker: String,
        crc: String,
    ): FrameNote = FrameNote(
        id = ring.nextId(),
        atMillis = atMillis,
        incoming = incoming,
        characteristic = characteristic,
        packetType = packetType,
        marker = marker,
        size = bytes.size,
        crc = crc,
        hex = HelloRedaction.hex(bytes),
    )
}

/** Packet 43 carries its layout at offset 9. Spec §6. */
private const val LIVE_LAYOUT: Int = 43

internal fun markerLabel(packetType: Int, at9: Int, at10: Int): String = when (packetType) {
    PacketType.COMMAND, PacketType.COMMAND_RESPONSE -> "opcode $at10"
    LIVE_LAYOUT, PacketType.HISTORY_RECORD -> "layout $at9"
    PacketType.EVENT -> "event $at10"
    PacketType.HISTORY_METADATA -> "kind $at10"
    else -> "b10 $at10"
}

internal fun crcLabel(reason: RejectReason): String = when (reason) {
    RejectReason.BAD_HEADER_CRC -> "header crc"
    RejectReason.BAD_BODY_CRC -> "body crc"
    RejectReason.TOO_SHORT -> "too short"
    RejectReason.BAD_START -> "bad start"
    RejectReason.BAD_FORMAT -> "bad format"
    RejectReason.FRAME_TOO_LARGE -> "too large"
    RejectReason.LENGTH_MISMATCH -> "length"
    RejectReason.LENGTH_ALIGNMENT -> "alignment"
}

private fun packetTypeOf(bytes: ByteArray): Int? {
    if (bytes.size < 11) return null
    if (bytes[0] != FrameBytes.START || bytes[1] != FrameBytes.FORMAT) return null
    return bytes[8].toInt() and 0xFF
}

/**
 * Hex for logs and exports.
 *
 * A command-145 response hides the three identity blocks from spec §7.
 * The marker is [MARKER]. Cleartext from those blocks is not copied out.
 */
internal object HelloRedaction {
    const val MARKER: String = "«redacted»"

    /**
     * Command-response body starts at frame offset 13: packet type, sequence,
     * command, origin sequence, then result. Spec §5.
     */
    private const val BODY_AT: Int = 13

    fun hex(bytes: ByteArray): String {
        val hidden = hiddenRanges(bytes)
        if (hidden.isEmpty()) return hexBytes(bytes)
        return buildString {
            var index = 0
            while (index < bytes.size) {
                val block = hidden.firstOrNull { index in it }
                if (block == null) {
                    appendSeparated("%02x".format(bytes[index].toInt() and 0xFF))
                    index += 1
                } else {
                    appendSeparated(MARKER)
                    index = minOf(block.last + 1, bytes.size)
                }
            }
        }
    }

    private fun StringBuilder.appendSeparated(token: String) {
        if (isNotEmpty()) append(' ')
        append(token)
    }

    private fun hiddenRanges(bytes: ByteArray): List<IntRange> {
        if (bytes.size < 11) return emptyList()
        if (bytes[0] != FrameBytes.START || bytes[1] != FrameBytes.FORMAT) return emptyList()
        val type = bytes[8].toInt() and 0xFF
        val command = bytes[10].toInt() and 0xFF
        if (type != PacketType.COMMAND_RESPONSE || command != HelloCommand.NUMBER) return emptyList()
        return HelloBody.IDENTITY.mapNotNull { range ->
            val start = BODY_AT + range.first
            if (start >= bytes.size) return@mapNotNull null
            val end = minOf(BODY_AT + range.last, bytes.size - 1)
            start..end
        }
    }
}

private fun hexBytes(bytes: ByteArray): String = buildString {
    bytes.forEachIndexed { index, value ->
        if (index > 0) append(' ')
        append("%02x".format(value.toInt() and 0xFF))
    }
}
