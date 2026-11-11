package app.pulse.sync

/**
 * Manual "Sync now" entry.
 *
 * [status] stays a single line for the attempt. [activity] is the live session
 * view: phase, chunk and record counts, saved-row counts, acknowledgement
 * counters, and the outcome of the attempt that just finished. A new attempt
 * clears that outcome.
 *
 * [historyAckEnabled] is the kill switch. It defaults to the caller's stored
 * value. [firmware] is read again for every chunk. [HistoryAckGate] stays
 * closed. This driver does not arm a one-shot and does not restore consent
 * from storage.
 */
class HistorySyncDriver(
    private val link: HistoryLink,
    private val store: SyncStore,
    private val phone: PhoneClock,
    private val schedule: HistoryScheduler,
    private val historyAckEnabled: () -> Boolean = { false },
    private val setHistoryAckEnabled: (Boolean) -> Unit = {},
    private val firmware: () -> String? = { null },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
) {
    var status: String = "Idle"
        private set

    var running: Boolean = false
        private set

    var activity: SyncActivity = SyncActivity(historyAckEnabled = false)
        private set

    var onChange: () -> Unit = {}

    private var engine: HistorySyncEngine? = null
    private var subscription: HistorySubscription? = null
    private var alarm: HistorySubscription? = null
    private var latest: SyncSnapshot = SyncSnapshot(SyncPhase.IDLE, 0, 0, null)

    /** Reads local-storage totals into [activity] while no attempt is running. */
    fun refreshSaved() {
        if (running) return
        val counts = try {
            store.savedCounts()
        } catch (error: Exception) {
            return
        }
        if (activity.savedSamples == counts.samples && activity.savedChunks == counts.chunks &&
            activity.historyAckEnabled == historyAckEnabled()
        ) {
            return
        }
        show(activity.copy(savedSamples = counts.samples, savedChunks = counts.chunks))
    }

    /** Publishes the kill switch onto [activity] before the first frame. */
    fun publishIdle() {
        if (running) return
        show(activity)
    }

    /**
     * Turns history acknowledgement on or off.
     *
     * A chunk already handed to the link is not recalled. The next chunk on
     * this attempt, and later attempts, follow [enabled].
     */
    fun setAckEnabled(enabled: Boolean) {
        setHistoryAckEnabled(enabled)
        log(if (enabled) "history ack on" else "history ack off")
        if (running) {
            show(activity)
            return
        }
        show(activity)
    }

    /**
     * @return true when a history attempt is running after this call.
     * A blank address does not count: nothing was written.
     */
    fun syncNow(): Boolean {
        if (running) return false
        val address = link.address
        if (address.isNullOrBlank()) {
            status = "Link a strap before syncing."
            show(activity.copy(blocked = status, running = false))
            return false
        }
        val created = HistorySyncEngine(
            address = address,
            store = store,
            phone = phone,
            transmit = { frame -> link.write(frame) },
            historyAckEnabled = historyAckEnabled,
            firmware = firmware,
            nowMs = nowMs,
            onFinished = { outcome -> finish(outcome) },
            onStreamPulse = { armWatchdog() },
            onProgress = { snap -> noteProgress(snap) },
            log = log,
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
            ackSent = 0,
            ackResult1 = 0,
            ackResult0 = 0,
            ackPending = 0,
            ackUnanswered = 0,
            ackRadioRefused = 0,
            ackSkipped = 0,
            ackEligibleRecords = 0,
            lastAckResult = null,
            lastAckKind = AckKind.NONE,
            ackNote = null,
        )
        show(activity)
        subscription = link.listen(
            onNotification = { source, bytes -> created.onNotification(source, bytes) },
            onDown = { created.onDisconnect() },
        )
        log("sync start")
        created.start()
        return running
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
            ackSent = snap.ackSent,
            ackResult1 = snap.ackResult1,
            ackResult0 = snap.ackResult0,
            ackPending = snap.ackPending,
            ackUnanswered = snap.ackUnanswered,
            ackRadioRefused = snap.ackRadioRefused,
            ackSkipped = snap.ackSkipped,
            ackEligibleRecords = snap.ackEligibleRecords,
            lastAckResult = snap.lastAckResult,
            lastAckKind = snap.lastAckKind,
            ackNote = snap.ackNote,
        )
        show(activity)
    }

    private fun show(next: SyncActivity) {
        activity = next.copy(historyAckEnabled = historyAckEnabled())
        onChange()
    }

    private fun finish(outcome: SessionOutcome) {
        if (!running) return
        val snap = latest
        running = false
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
            historyAckEnabled = historyAckEnabled(),
            ackSent = snap.ackSent,
            ackResult1 = snap.ackResult1,
            ackResult0 = snap.ackResult0,
            ackPending = snap.ackPending,
            ackUnanswered = snap.ackUnanswered,
            ackRadioRefused = snap.ackRadioRefused,
            ackSkipped = snap.ackSkipped,
            ackEligibleRecords = snap.ackEligibleRecords,
            lastAckResult = snap.lastAckResult,
            lastAckKind = snap.lastAckKind,
            ackNote = snap.ackNote,
        )
        onChange()
        log("sync closed $outcome")
    }
}
