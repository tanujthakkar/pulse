package app.pulse.sync

import app.pulse.wire.codec.HistoryAckGate

/**
 * Manual "Sync now" entry.
 *
 * [status] stays a single line for the attempt. [activity] is the live session
 * view: phase, chunk and record counts, and the last outcome with its time.
 *
 * Production leaves [ackEnabled] on [HistoryAckGate.maySend], which stays
 * false until a Pulse capture confirms the metadata offsets. The engine still
 * runs the clock, range, and history-start steps. This driver does not open
 * that gate.
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

    fun syncNow() {
        if (running) return
        val address = link.address
        if (address.isNullOrBlank()) {
            status = "Link a strap before syncing."
            activity = activity.copy(blocked = status, running = false)
            onChange()
            return
        }
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
            blocked = null,
        )
        subscription = link.listen(
            onNotification = { source, bytes -> created.onNotification(source, bytes) },
            onDown = { created.onDisconnect() },
        )
        onChange()
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
            blocked = null,
        )
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
            outcome = outcome,
            finishedAt = phone.now(),
            blocked = null,
        )
        log("sync closed $outcome")
        onChange()
    }
}
