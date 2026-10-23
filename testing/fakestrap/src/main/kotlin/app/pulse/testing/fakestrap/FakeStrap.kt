package app.pulse.testing.fakestrap

import app.pulse.wire.ProtocolSpecVersion
import app.pulse.wire.codec.ClockCommand
import app.pulse.wire.codec.CommandResult
import app.pulse.wire.codec.DataRangeCommand
import app.pulse.wire.codec.DecodeResult
import app.pulse.wire.codec.FrameCodec
import app.pulse.wire.codec.HeaderWord
import app.pulse.wire.codec.HistoryAckCommand
import app.pulse.wire.codec.HistoryMetadata
import app.pulse.wire.codec.HistoryRecordDecoder
import app.pulse.wire.codec.HistoryStartCommand
import app.pulse.wire.codec.PacketType
import app.pulse.wire.codec.WireFrame
import java.util.ArrayDeque

/**
 * Scripted strap for history-sync tests.
 *
 * It answers clock, data-range, and history-start commands, then emits the
 * scripted metadata and records. It does not use a radio. Command 23 is
 * recorded and answered; the script does not require the token to be rebuilt.
 */
class FakeStrap(
    private val script: StrapScript = StrapScript(),
) {
    val specVersion: String = ProtocolSpecVersion.CURRENT

    var connected: Boolean = true
        private set

    val ackTokens: List<ByteArray>
        get() = acks.toList()

    private val outgoing = ArrayDeque<ByteArray>()
    private val acks = mutableListOf<ByteArray>()
    private var strapSeconds = script.strapUnixSeconds
    private var strapTicks = script.strapTicks
    private var responseSequence = 1
    private var historyStarts = 0
    private var ackCount = 0
    private var chunkIndex = 0
    private var streamArmed = false

    fun accept(frame: ByteArray) {
        if (!connected) return
        val decoded = FrameCodec.decode(frame)
        if (decoded !is DecodeResult.Ok) return
        val wire = decoded.frame
        if (wire.packetType != PacketType.COMMAND) return
        when (wire.command) {
            ClockCommand.GET -> reply(ClockCommand.GET, wire.sequence, CommandResult.SUCCESS, clockBody())
            ClockCommand.SET -> applySet(wire)
            DataRangeCommand.NUMBER -> {
                reply(DataRangeCommand.NUMBER, wire.sequence, CommandResult.PENDING, ByteArray(0))
                reply(DataRangeCommand.NUMBER, wire.sequence, CommandResult.SUCCESS, script.range.copyOf())
            }
            HistoryStartCommand.NUMBER -> onHistoryStart(wire.sequence)
            HistoryAckCommand.NUMBER -> onAck(wire)
        }
    }

    fun poll(): ByteArray? = if (outgoing.isEmpty()) null else outgoing.removeFirst()

    fun hasOutgoing(): Boolean = outgoing.isNotEmpty()

    private fun applySet(wire: WireFrame) {
        val clock = ClockCommand.parse(wire.tail)
        if (clock == null) {
            reply(ClockCommand.SET, wire.sequence, CommandResult.FAILURE, byteArrayOf(ClockCommand.REVISION.toByte()))
            return
        }
        strapSeconds = clock.unixSeconds
        strapTicks = clock.ticks
        reply(ClockCommand.SET, wire.sequence, CommandResult.SUCCESS, byteArrayOf(ClockCommand.REVISION.toByte()))
    }

    private fun onHistoryStart(origin: Int) {
        val forced = script.startResults.getOrNull(historyStarts)
        historyStarts += 1
        if (forced != null && forced != CommandResult.SUCCESS) {
            reply(HistoryStartCommand.NUMBER, origin, forced, byteArrayOf(0, 0, 0))
            return
        }
        val state = script.startState
        reply(
            HistoryStartCommand.NUMBER,
            origin,
            CommandResult.SUCCESS,
            byteArrayOf(state.toByte(), 0, 0),
        )
        if (state in HistoryStartCommand.FAIL_STATES || streamArmed) return
        streamArmed = true
        emitStream()
    }

    private fun emitStream() {
        when (script.stream) {
            StreamKind.SILENT -> Unit
            StreamKind.FLOOD -> {
                enqueue(metadata(HistoryMetadata.START, null))
                repeat(script.floodCount) { index ->
                    enqueue(unknownRecord(layout = 99, mark = index))
                }
            }
            StreamKind.DISCONNECT -> {
                enqueue(metadata(HistoryMetadata.START, null))
                script.chunks.firstOrNull()?.records?.forEach { enqueue(it) }
                connected = false
            }
            StreamKind.COMPLETE_WITHOUT_END -> {
                enqueue(metadata(HistoryMetadata.START, null))
                script.chunks.firstOrNull()?.records?.forEach { enqueue(it) }
                enqueue(metadata(HistoryMetadata.COMPLETE, null))
            }
            StreamKind.CHUNKS -> emitChunk(0)
        }
    }

    private fun emitChunk(index: Int) {
        val chunk = script.chunks.getOrNull(index) ?: return
        chunkIndex = index
        enqueue(metadata(HistoryMetadata.START, null))
        chunk.records.forEach { enqueue(it) }
        if (chunk.omitToken) {
            enqueue(shortEnd())
        } else {
            repeat(1 + chunk.extraEnds) {
                enqueue(metadata(HistoryMetadata.END, chunk.token))
            }
        }
    }

    private fun onAck(wire: WireFrame) {
        if (wire.tail.size >= 1 + HistoryAckCommand.TOKEN_BYTES &&
            (wire.tail[0].toInt() and 0xFF) == HistoryAckCommand.PREFIX
        ) {
            acks += wire.tail.copyOfRange(1, 1 + HistoryAckCommand.TOKEN_BYTES)
        }
        reply(HistoryAckCommand.NUMBER, wire.sequence, CommandResult.SUCCESS, ByteArray(0))
        ackCount += 1
        val chunk = script.chunks.getOrNull(chunkIndex) ?: return
        val expected = 1 + chunk.extraEnds
        if (ackCount < expected) return
        ackCount = 0
        val next = chunkIndex + 1
        if (next < script.chunks.size) {
            emitChunk(next)
        } else if (script.completeAfterChunks) {
            enqueue(metadata(HistoryMetadata.COMPLETE, null))
        }
    }

    private fun clockBody(): ByteArray = ClockCommand.setBody(strapSeconds, strapTicks.coerceIn(0, ClockCommand.MAX_TICKS))

    private fun reply(command: Int, origin: Int, result: Int, body: ByteArray) {
        val sequence = responseSequence
        responseSequence = (responseSequence + 1) and 0xFF
        outgoing += FrameCodec.encodeCommandResponse(
            sequence = sequence,
            command = command,
            originSequence = origin,
            result = result,
            body = body,
        )
    }

    private fun enqueue(frame: ByteArray) {
        outgoing += frame
    }

    private fun metadata(kind: Int, token: ByteArray?): ByteArray {
        val body = ByteArray(18)
        if (token != null) token.copyInto(body, destinationOffset = 10)
        return FrameCodec.encode(
            headerWord = HeaderWord.COMMON_RESPONSE,
            packetType = PacketType.HISTORY_METADATA,
            sequence = 1,
            command = kind,
            body = body,
        )
    }

    private fun shortEnd(): ByteArray {
        return FrameCodec.encode(
            headerWord = HeaderWord.COMMON_RESPONSE,
            packetType = PacketType.HISTORY_METADATA,
            sequence = 1,
            command = HistoryMetadata.END,
            body = ByteArray(0),
        )
    }
}

enum class StreamKind {
    CHUNKS,
    SILENT,
    FLOOD,
    DISCONNECT,
    COMPLETE_WITHOUT_END,
}

class StrapScript(
    val strapUnixSeconds: Long = 1_700_000_000L,
    val strapTicks: Int = 100,
    val range: ByteArray = defaultRange(),
    val startResults: List<Int> = listOf(CommandResult.SUCCESS),
    val startState: Int = 1,
    val chunks: List<ScriptChunk> = listOf(ScriptChunk(sampleToken(), listOf(sampleRecord()))),
    val stream: StreamKind = StreamKind.CHUNKS,
    val floodCount: Int = 2_000,
    val completeAfterChunks: Boolean = true,
)

class ScriptChunk(
    val token: ByteArray,
    val records: List<ByteArray>,
    val extraEnds: Int = 0,
    val omitToken: Boolean = false,
)

fun defaultRange(): ByteArray = ByteArray(DataRangeCommand.BODY_BYTES) { index ->
    if (index == 0) 1 else index.toByte()
}

fun sampleToken(): ByteArray = byteArrayOf(
    0xFE.toByte(),
    0xFE.toByte(),
    0xFE.toByte(),
    0xFE.toByte(),
    0xFD.toByte(),
    0xFD.toByte(),
    0xFD.toByte(),
    0xFD.toByte(),
)

/** One layout-18 frame the history decoder accepts. */
fun sampleRecord(
    recordIndex: Long = 41,
    unixSeconds: Long = 1_700_000_100L,
    heartRate: Int = 72,
): ByteArray {
    val body = ByteArray(109)
    putU32(body, 0, recordIndex)
    putU32(body, 4, unixSeconds)
    body[11] = heartRate.toByte()
    body[12] = 1
    putU16(body, 13, 1024)
    putU16(body, 62, 3650)
    body[52] = 1
    return FrameCodec.encode(
        headerWord = HeaderWord.COMMON_REQUEST,
        packetType = PacketType.HISTORY_RECORD,
        sequence = HistoryRecordDecoder.LAYOUT,
        command = 0,
        body = body,
    )
}

/** A short history record whose layout is not 18. */
fun unknownRecord(layout: Int, mark: Int = 1): ByteArray {
    return FrameCodec.encode(
        headerWord = HeaderWord.COMMON_REQUEST,
        packetType = PacketType.HISTORY_RECORD,
        sequence = layout and 0xFF,
        command = 0,
        body = byteArrayOf(mark.toByte(), (mark ushr 8).toByte(), 0x5A),
    )
}

fun eventFrame(): ByteArray {
    return FrameCodec.encode(
        headerWord = HeaderWord.COMMON_RESPONSE,
        packetType = PacketType.EVENT,
        sequence = 0,
        command = 3,
        body = byteArrayOf(1, 0, 0, 0),
    )
}

private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value and 0xFF).toByte()
    bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
    val bits = value and 0xFFFF_FFFFL
    bytes[offset] = (bits and 0xFF).toByte()
    bytes[offset + 1] = ((bits ushr 8) and 0xFF).toByte()
    bytes[offset + 2] = ((bits ushr 16) and 0xFF).toByte()
    bytes[offset + 3] = ((bits ushr 24) and 0xFF).toByte()
}

/**
 * JVM stand-in reserved for later scripted strap tests.
 *
 * It does not talk to a radio. The serialized GATT session is tested in `:ble`
 * with an in-memory radio, because this module cannot depend on Android.
 */
class FakeGattSession
