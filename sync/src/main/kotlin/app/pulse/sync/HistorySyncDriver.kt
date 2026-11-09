package app.pulse.sync

import app.pulse.wire.codec.FrameCodec
import app.pulse.wire.codec.HeaderWord
import app.pulse.wire.codec.HistoryAckCommand
import app.pulse.wire.codec.HistoryAckGate
import app.pulse.wire.codec.PacketType

/**
 * Manual "Sync now" entry.
 *
 * [status] stays a single line for the attempt. [activity] is the live session
 * view: phase, chunk and record counts, saved-row counts, and the outcome of
 * the attempt that just finished. A new attempt clears that outcome.
 *
 * Production leaves [ackEnabled] on [HistoryAckGate.maySend], which stays
 * false. The engine still runs the clock, range, and history-start steps.
 * This driver does not open that gate. [armOneShot] and [sendOneShot] are a
 * separate in-memory consent. They start off, clear on disconnect, and send
 * at most one command 23 after the chunk is already stored.
 */
class HistorySyncDriver(
    private val link: HistoryLink,
    private val store: SyncStore,
    private val phone: PhoneClock,
    private val schedule: HistoryScheduler,
    private val ackEnabled: () -> Boolean = { HistoryAckGate.maySend() },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
) {
    var status: String = "Idle"
        private set

    var running: Boolean = false
        private set

    var activity: SyncActivity = SyncActivity()
        private set

    var onChange: () -> Unit = {}

    private var engine: HistorySyncEngine? = null
    private var subscription: HistorySubscription? = null
    private var alarm: HistorySubscription? = null
    private var latest: SyncSnapshot = SyncSnapshot(SyncPhase.IDLE, 0, 0, null)
    private var offer: DurableChunk? = null
    private var armed = false
    private var spent = false
    private var offersThisAttempt = 0
    private var ackNote: String? = null
    private var oneShotSequence = 1

    /** Reads local-storage totals into [activity] while no attempt is running. */
    fun refreshSaved() {
        if (running) return
        val counts = try {
            store.savedCounts()
        } catch (error: Exception) {
            return
        }
        if (activity.savedSamples == counts.samples && activity.savedChunks == counts.chunks) return
        show(activity.copy(savedSamples = counts.samples, savedChunks = counts.chunks))
    }

    /** Local only. Does not write to the strap. */
    fun armOneShot(): Boolean {
        val waiting = offer
        if (running || spent || waiting == null || !HistoryAckCommand.sendable(waiting.token)) return false
        armed = true
        ackNote = null
        show(activity)
        return true
    }

    /**
     * Sends one command 23 for the offered chunk, then turns consent off.
     *
     * A refusal also turns consent off and does not retry. [firmware] must be
     * [OneShotAck.FIRMWARE]. The chunk is read again from [store] before the
     * write. Nothing is sent while an attempt is running, while consent is
     * off, or when the stored row is missing or already marked.
     */
    fun sendOneShot(firmware: String?) {
        val waiting = offer
        if (running || !armed || spent || waiting == null) {
            noteRefused("Arm one acknowledgement before sending it.")
            return
        }
        armed = false
        spent = true
        if (!OneShotAck.allowsFirmware(firmware)) {
            clearOffer("Send stayed off because the hello firmware is not ${OneShotAck.FIRMWARE}.")
            return
        }
        if (!HistoryAckCommand.sendable(waiting.token)) {
            clearOffer("This token is not acknowledged.")
            return
        }
        if (link.address.isNullOrBlank()) {
            clearOffer("The strap link is down. The acknowledgement was not sent.")
            return
        }
        val stored = try {
            store.persistedChunk(waiting.sessionId, waiting.token)
        } catch (error: Exception) {
            null
        }
        if (stored == null ||
            stored.ackedAt != null ||
            stored.chunkId != waiting.chunkId ||
            stored.sampleCount != waiting.sampleCount
        ) {
            clearOffer("The saved chunk is not available. The acknowledgement was not sent.")
            return
        }
        val sequence = oneShotSequence
        oneShotSequence = (oneShotSequence + 1) and 0xFF
        val frame = FrameCodec.encode(
            headerWord = HeaderWord.COMMON_REQUEST,
            packetType = PacketType.COMMAND,
            sequence = sequence,
            command = HistoryAckCommand.NUMBER,
            body = HistoryAckCommand.body(waiting.token),
        )
        if (!link.write(frame)) {
            clearOffer("The acknowledgement was not sent and will not be retried.")
            return
        }
        try {
            store.markAcknowledged(stored.sessionId, waiting.token, phone.now().unixSeconds)
        } catch (error: Exception) {
            clearOffer("One acknowledgement was sent. It will not be retried.")
            return
        }
        clearOffer("One acknowledgement was sent. It will not be sent again until you arm another.")
    }

    /** Drops consent and the offered chunk. Used when the strap link drops. */
    fun dropOneShot() {
        offer = null
        armed = false
        spent = false
        offersThisAttempt = 0
        ackNote = null
        show(activity)
    }

    fun syncNow() {
        if (running) return
        val address = link.address
        if (address.isNullOrBlank()) {
            status = "Link a strap before syncing."
            show(activity.copy(blocked = status, running = false))
            return
        }
        offer = null
        armed = false
        spent = false
        offersThisAttempt = 0
        ackNote = null
        val created = HistorySyncEngine(
            address = address,
            store = store,
            phone = phone,
            transmit = { frame -> link.write(frame) },
            ackEnabled = ackEnabled,
            nowMs = nowMs,
            onFinished = { outcome -> finish(outcome) },
            onStreamPulse = { armWatchdog() },
            onProgress = { snap -> noteProgress(snap) },
            onDurableChunk = { chunk -> noteDurable(chunk) },
        )
        engine = created
        running = true
        status = "Syncing"
        latest = SyncSnapshot(SyncPhase.CLOCK, 0, 0, null)
        activity = activity.copy(
            phase = SyncPhase.CLOCK,
            running = true,
            chunks = 0,
            records = 0,
            endsReceived = 0,
            chunksDuplicate = 0,
            endsRetried = 0,
            endsRejected = 0,
            ignoredCompletes = 0,
            droppedUncommitted = false,
            outcome = null,
            finishedAt = null,
            blocked = null,
        )
        show(activity)
        subscription = link.listen(
            onNotification = { source, bytes -> created.onNotification(source, bytes) },
            onDown = { created.onDisconnect() },
        )
        log("sync start")
        created.start()
    }

    private fun armWatchdog() {
        alarm?.cancel()
        val wait = engine?.quietRemainingMs() ?: return
        alarm = schedule.after(wait) {
            val current = engine ?: return@after
            if (!current.onWatchdog()) armWatchdog()
        }
    }

    private fun noteProgress(snap: SyncSnapshot) {
        latest = snap
        if (!running || snap.outcome != null) return
        activity = activity.copy(
            phase = snap.phase,
            running = true,
            chunks = snap.chunks,
            records = snap.records,
            endsReceived = snap.endsReceived,
            chunksDuplicate = snap.chunksDuplicate,
            endsRetried = snap.endsRetried,
            endsRejected = snap.endsRejected,
            ignoredCompletes = snap.ignoredCompletes,
            droppedUncommitted = false,
            savedSamples = snap.savedSamples,
            savedChunks = snap.savedChunks,
            outcome = null,
            finishedAt = null,
            blocked = null,
        )
        show(activity)
    }

    private fun noteDurable(chunk: DurableChunk) {
        if (!HistoryAckCommand.sendable(chunk.token)) {
            ackNote = "This token is not acknowledged."
            return
        }
        offersThisAttempt += 1
        if (offersThisAttempt > 1) {
            offer = null
            armed = false
            ackNote = "This attempt saved more than one chunk. No acknowledgement is offered."
            return
        }
        offer = DurableChunk(
            chunkId = chunk.chunkId,
            sessionId = chunk.sessionId,
            token = chunk.token.copyOf(),
            sampleCount = chunk.sampleCount,
        )
        armed = false
        spent = false
        ackNote = null
    }

    private fun noteRefused(reason: String) {
        armed = false
        ackNote = reason
        show(activity)
    }

    private fun clearOffer(reason: String) {
        offer = null
        armed = false
        ackNote = reason
        show(activity)
    }

    private fun show(next: SyncActivity) {
        activity = next.copy(
            ackSamples = offer?.sampleCount,
            ackArmed = armed && offer != null,
            ackSpent = spent,
            ackNote = ackNote,
        )
        onChange()
    }

    private fun finish(outcome: SessionOutcome) {
        if (!running) return
        val snap = latest
        running = false
        if (outcome == SessionOutcome.DISCONNECTED) {
            offer = null
            armed = false
            spent = false
            offersThisAttempt = 0
            ackNote = null
        }
        alarm?.cancel()
        alarm = null
        subscription?.cancel()
        subscription = null
        engine = null
        status = when (outcome) {
            SessionOutcome.COMPLETE -> "Sync finished."
            else -> "Sync stopped."
        }
        activity = SyncActivity(
            phase = SyncPhase.DONE,
            running = false,
            chunks = snap.chunks,
            records = snap.records,
            endsReceived = snap.endsReceived,
            chunksDuplicate = snap.chunksDuplicate,
            endsRetried = snap.endsRetried,
            endsRejected = snap.endsRejected,
            ignoredCompletes = snap.ignoredCompletes,
            droppedUncommitted = snap.droppedUncommitted,
            savedSamples = snap.savedSamples,
            savedChunks = snap.savedChunks,
            outcome = outcome,
            finishedAt = phone.now(),
            blocked = null,
        )
        show(activity)
        log("sync closed $outcome")
    }
}
