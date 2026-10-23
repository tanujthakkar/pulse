package app.pulse.sync

import app.pulse.wire.ProtocolSpecVersion
import app.pulse.wire.codec.AccumulatorEvent
import app.pulse.wire.codec.ClockCommand
import app.pulse.wire.codec.CommandFrames
import app.pulse.wire.codec.CommandResult
import app.pulse.wire.codec.ConnectionId
import app.pulse.wire.codec.DataRangeCommand
import app.pulse.wire.codec.DeniedCommands
import app.pulse.wire.codec.FrameAccumulator
import app.pulse.wire.codec.FrameCodec
import app.pulse.wire.codec.HeaderWord
import app.pulse.wire.codec.HistoryAckCommand
import app.pulse.wire.codec.HistoryMetadata
import app.pulse.wire.codec.HistoryRecord
import app.pulse.wire.codec.HistoryRecordDecoder
import app.pulse.wire.codec.HistoryStartCommand
import app.pulse.wire.codec.PacketType
import app.pulse.wire.codec.ReplyMatch
import app.pulse.wire.codec.RequestTracker
import app.pulse.wire.codec.WireFrame
import app.pulse.wire.codec.toCommandResponse
import java.util.concurrent.atomic.AtomicLong

/**
 * One manual history attempt.
 *
 * Sequence, from spec §7, §8, and project rules 6–11: clock get, clock set
 * when the strap is more than [HistoryLimits.CLOCK_DRIFT_SECONDS] from the
 * phone, clock get again, data range, open a session with that 65-byte
 * snapshot, history start with an explicit `00`, then metadata and records.
 * Each metadata end is committed
 * and only then acknowledged. The acknowledgement body is `01` plus the
 * original token bytes.
 *
 * [ackEnabled] is the production gate. When it is false the chunk is still
 * committed and no command 23 frame is handed to [transmit]. Callers serialize
 * [start], [onNotification], [onDisconnect], and [onWatchdog].
 *
 * Commands sent from here are clock get, clock set, data range, history start,
 * and, only when [ackEnabled] is true, history ack. Deny-listed cursor commands
 * are not sent.
 */
class HistorySyncEngine(
    private val address: String,
    private val store: SyncStore,
    private val phone: PhoneClock,
    private val transmit: (ByteArray) -> Boolean,
    private val ackEnabled: () -> Boolean,
    private val nowMs: () -> Long = { 0L },
    private val onFinished: (SessionOutcome) -> Unit = {},
    private val onStreamPulse: () -> Unit = {},
) {
    val specVersion: String = ProtocolSpecVersion.CURRENT

    var outcome: SessionOutcome? = null
        private set

    var rangeSnapshot: ByteArray? = null
        private set

    val isActive: Boolean
        get() = active

    val events: List<SyncTrace>
        get() = trace.toList()

    private val connection = ConnectionId(NEXT_CONNECTION.incrementAndGet())
    private val tracker = RequestTracker()
    private val incoming = HashMap<String, FrameAccumulator>()
    private val trace = mutableListOf<SyncTrace>()
    private val records = mutableListOf<WireFrame>()
    private val side = mutableListOf<WireFrame>()
    private val archivedSide = mutableListOf<WireFrame>()

    private var active = false
    private var phase = Phase.IDLE
    private var sequence = 1
    private var sessionId: Long? = null
    private var sessionClosed = false
    private var strapUnix: Long? = null
    private var strapTicks: Int? = null
    private var startRetried = false
    private var lastTrafficMs = 0L

    fun start() {
        if (active) return
        if (address.isBlank()) {
            outcome = SessionOutcome.ABORTED
            trace += SyncTrace.Closed(SessionOutcome.ABORTED)
            onFinished(SessionOutcome.ABORTED)
            return
        }
        active = true
        phase = Phase.CLOCK
        send(ClockCommand.GET, ClockCommand.getBody())
    }

    fun onNotification(source: String, bytes: ByteArray) {
        if (!active) return
        val accumulator = incoming.getOrPut(source) { FrameAccumulator() }
        for (event in accumulator.append(bytes)) {
            if (!active) return
            if (event is AccumulatorEvent.Frame) onFrame(event.frame)
        }
    }

    fun onDisconnect() {
        if (!active) return
        dropChunk()
        finish(SessionOutcome.DISCONNECTED)
    }

    /**
     * Ends the attempt when history traffic has been quiet for
     * [HistoryLimits.WATCHDOG_MS]. An open chunk is dropped and not acknowledged.
     *
     * @return true when the quiet window has elapsed and the attempt ended.
     */
    fun onWatchdog(): Boolean {
        if (!active || phase != Phase.STREAM) return false
        if (nowMs() - lastTrafficMs < HistoryLimits.WATCHDOG_MS) return false
        dropChunk()
        finish(SessionOutcome.WATCHDOG)
        return true
    }

    /** Milliseconds until [onWatchdog] would end the attempt, or null outside the stream. */
    fun quietRemainingMs(): Long? {
        if (!active || phase != Phase.STREAM) return null
        val left = HistoryLimits.WATCHDOG_MS - (nowMs() - lastTrafficMs)
        return if (left < 0) 0 else left
    }

    private fun onFrame(frame: WireFrame) {
        if (!active) return
        if (phase == Phase.STREAM && frame.packetType in HistoryLimits.TRAFFIC) {
            lastTrafficMs = nowMs()
            onStreamPulse()
        }
        val response = frame.toCommandResponse()
        if (response != null) {
            when (val match = tracker.onFrame(connection, frame)) {
                is ReplyMatch.Matched -> onMatched(match, response.body)
                is ReplyMatch.Unmatched -> Unit
            }
            return
        }
        if (phase != Phase.STREAM) return
        when (frame.packetType) {
            PacketType.HISTORY_RECORD -> onRecord(frame)
            PacketType.HISTORY_METADATA -> onMetadata(frame)
            PacketType.EVENT, PacketType.CONSOLE -> side += frame
            else -> archivedSide += frame
        }
    }

    private fun onMatched(match: ReplyMatch.Matched, body: ByteArray) {
        if (!active) return
        when (phase) {
            Phase.CLOCK -> onClock(match, body, verifying = false)
            Phase.CLOCK_VERIFY -> onClock(match, body, verifying = true)
            Phase.CLOCK_SET -> onClockSet(match)
            Phase.RANGE -> onRange(match, body)
            Phase.START -> onHistoryStart(match, body)
            Phase.STREAM -> Unit
            Phase.IDLE, Phase.DONE -> Unit
        }
    }

    private fun onClock(match: ReplyMatch.Matched, body: ByteArray, verifying: Boolean) {
        if (match.command != ClockCommand.GET || match.pending) return
        if (!match.succeeded) {
            fail(SessionOutcome.ABORTED)
            return
        }
        val clock = ClockCommand.parse(body)
        if (clock == null || clock.unixSeconds == 0L) {
            fail(SessionOutcome.ABORTED)
            return
        }
        strapUnix = clock.unixSeconds
        strapTicks = clock.ticks
        if (!verifying && drifted(clock.unixSeconds)) {
            val now = phone.now()
            if (now.unixSeconds !in 0..0xFFFF_FFFFL || now.ticks !in 0..ClockCommand.MAX_TICKS) {
                fail(SessionOutcome.ABORTED)
                return
            }
            phase = Phase.CLOCK_SET
            send(ClockCommand.SET, ClockCommand.setBody(now.unixSeconds, now.ticks))
            return
        }
        beginRange()
    }

    private fun onClockSet(match: ReplyMatch.Matched) {
        if (match.command != ClockCommand.SET || match.pending) return
        phase = Phase.CLOCK_VERIFY
        send(ClockCommand.GET, ClockCommand.getBody())
    }

    private fun beginRange() {
        phase = Phase.RANGE
        send(DataRangeCommand.NUMBER, DataRangeCommand.requestBody())
    }

    private fun onRange(match: ReplyMatch.Matched, body: ByteArray) {
        if (match.command != DataRangeCommand.NUMBER || match.pending) return
        if (!match.succeeded) {
            fail(SessionOutcome.ABORTED)
            return
        }
        val snapshot = DataRangeCommand.snapshot(body)
        val seconds = strapUnix
        if (snapshot == null || seconds == null) {
            fail(SessionOutcome.ABORTED)
            return
        }
        val id = try {
            store.openSession(address, seconds, strapTicks, snapshot)
        } catch (error: Exception) {
            fail(SessionOutcome.ABORTED)
            return
        }
        sessionId = id
        rangeSnapshot = snapshot.copyOf()
        trace += SyncTrace.Opened(id, snapshot.copyOf())
        phase = Phase.START
        send(HistoryStartCommand.NUMBER, HistoryStartCommand.EXPLICIT_ZERO.copyOf())
    }

    private fun onHistoryStart(match: ReplyMatch.Matched, body: ByteArray) {
        if (match.command != HistoryStartCommand.NUMBER || match.pending) return
        if (match.result == CommandResult.FAILURE || match.result == CommandResult.UNSUPPORTED) {
            if (!startRetried) {
                startRetried = true
                send(HistoryStartCommand.NUMBER, HistoryStartCommand.EMPTY.copyOf())
                return
            }
            fail(SessionOutcome.ABORTED)
            return
        }
        if (!match.succeeded) {
            fail(SessionOutcome.ABORTED)
            return
        }
        val state = HistoryStartCommand.state(body)
        if (state == null || state in HistoryStartCommand.FAIL_STATES) {
            fail(SessionOutcome.ABORTED)
            return
        }
        phase = Phase.STREAM
        lastTrafficMs = nowMs()
        onStreamPulse()
    }

    private fun onRecord(frame: WireFrame) {
        records += frame
        if (records.size >= HistoryLimits.MAX_RECORDS_WITHOUT_END) {
            dropChunk()
            finish(SessionOutcome.BOUNDED)
        }
    }

    private fun onMetadata(frame: WireFrame) {
        val view = HistoryMetadata.read(frame) ?: return
        when (view.kind) {
            HistoryMetadata.START -> Unit
            HistoryMetadata.END -> {
                val token = view.token
                if (token == null || token.size != HISTORY_TOKEN_BYTES) {
                    dropChunk()
                    finish(SessionOutcome.ABORTED)
                    return
                }
                commitThenAck(token)
            }
            HistoryMetadata.COMPLETE -> {
                if (buffered()) dropChunk()
                finish(SessionOutcome.COMPLETE)
            }
        }
    }

    private fun commitThenAck(token: ByteArray) {
        val session = sessionId
        if (session == null) {
            dropChunk()
            finish(SessionOutcome.ABORTED)
            return
        }
        val kept = token.copyOf()
        val frames = ArrayList<RawFrameDraft>()
        val samples = ArrayList<R18Draft>()
        val archived = ArrayList<RawFrameDraft>()
        for (frame in records) {
            when (val decoded = HistoryRecordDecoder.decode(frame.bytes)) {
                is HistoryRecord.Layout18 -> {
                    samples += decoded.toR18Draft()
                    frames += RawFrameDraft(
                        bytes = frame.bytes.copyOf(),
                        packetType = PacketType.HISTORY_RECORD,
                        layout = HistoryRecordDecoder.LAYOUT,
                        recordIndex = decoded.stored.recordIndex,
                        unixSeconds = decoded.stored.unixSeconds,
                    )
                }
                is HistoryRecord.Unknown -> archived += decoded.toRawFrameDraft()
                is HistoryRecord.Rejected -> archived += RawFrameDraft(
                    bytes = frame.bytes.copyOf(),
                    packetType = frame.packetType,
                    layout = frame.bytes.getOrNull(9)?.toInt()?.and(0xFF),
                )
            }
        }
        for (frame in side) {
            frames += RawFrameDraft(
                bytes = frame.bytes.copyOf(),
                packetType = frame.packetType,
            )
        }
        for (frame in archivedSide) {
            archived += RawFrameDraft(
                bytes = frame.bytes.copyOf(),
                packetType = frame.packetType,
                layout = frame.bytes.getOrNull(9)?.toInt()?.and(0xFF),
            )
        }
        val receipt = try {
            store.commitChunk(session, frames, samples, archived, kept)
        } catch (error: Exception) {
            clearBuffer()
            finish(SessionOutcome.ABORTED)
            return
        }
        clearBuffer()
        trace += SyncTrace.Commit(
            token = kept,
            frames = receipt.framesInserted,
            samples = receipt.samplesInserted,
            archived = receipt.archivedInserted,
            duplicate = receipt.duplicate,
        )
        if (!active || !ackEnabled()) return
        val body = HistoryAckCommand.body(kept)
        val seq = nextSequence()
        val ackFrame = FrameCodec.encode(
            headerWord = HeaderWord.COMMON_REQUEST,
            packetType = PacketType.COMMAND,
            sequence = seq,
            command = HistoryAckCommand.NUMBER,
            body = body,
        )
        tracker.open(connection, HistoryAckCommand.NUMBER, seq)
        if (!transmit(ackFrame)) {
            finish(SessionOutcome.DISCONNECTED)
            return
        }
        trace += SyncTrace.Ack(kept.copyOf(), body.copyOf())
        try {
            store.markAcknowledged(session, kept, phone.now().unixSeconds)
        } catch (error: Exception) {
            finish(SessionOutcome.ABORTED)
        }
    }

    private fun send(command: Int, body: ByteArray): Boolean {
        if (!active) return false
        check(command != HistoryAckCommand.NUMBER) { "history ack is sent only after commit" }
        check(DeniedCommands.allows(command)) { "command $command is not sent" }
        val seq = nextSequence()
        val frame = CommandFrames.request(command, seq, body)
        tracker.open(connection, command, seq)
        trace += SyncTrace.Sent(command, body.copyOf())
        if (!transmit(frame)) {
            finish(SessionOutcome.DISCONNECTED)
            return false
        }
        return active
    }

    private fun drifted(strapSeconds: Long): Boolean {
        val delta = strapSeconds - phone.now().unixSeconds
        val gap = if (delta < 0) -delta else delta
        return gap > HistoryLimits.CLOCK_DRIFT_SECONDS
    }

    private fun dropChunk() {
        if (!buffered()) return
        clearBuffer()
        trace += SyncTrace.DroppedChunk
    }

    private fun buffered(): Boolean =
        records.isNotEmpty() || side.isNotEmpty() || archivedSide.isNotEmpty()

    private fun clearBuffer() {
        records.clear()
        side.clear()
        archivedSide.clear()
    }

    private fun fail(outcome: SessionOutcome) {
        if (!active) return
        dropChunk()
        finish(outcome)
    }

    private fun finish(outcome: SessionOutcome) {
        if (!active) return
        active = false
        phase = Phase.DONE
        this.outcome = outcome
        val session = sessionId
        if (session != null && !sessionClosed) {
            sessionClosed = true
            try {
                store.closeSession(session, phone.now().unixSeconds, null)
            } catch (error: Exception) {
                // The attempt is over. Closing must not acknowledge an open chunk.
            }
        }
        incoming.values.forEach { it.reset() }
        tracker.dropConnection(connection)
        trace += SyncTrace.Closed(outcome)
        onFinished(outcome)
    }

    private fun nextSequence(): Int {
        val current = sequence
        sequence = (sequence + 1) and 0xFF
        return current
    }

    private enum class Phase {
        IDLE,
        CLOCK,
        CLOCK_SET,
        CLOCK_VERIFY,
        RANGE,
        START,
        STREAM,
        DONE,
    }

    private companion object {
        val NEXT_CONNECTION = AtomicLong(0)
    }
}
