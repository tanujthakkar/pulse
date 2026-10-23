package app.pulse.sync

import app.pulse.wire.codec.HistoryAckGate

/**
 * Manual "Sync now" entry. Full sync status is a later screen.
 *
 * Production leaves [ackEnabled] on [HistoryAckGate.maySend], which stays
 * false until a Pulse capture confirms the metadata offsets. The engine still
 * runs the clock, range, and history-start steps.
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

    var onChange: () -> Unit = {}

    private var engine: HistorySyncEngine? = null
    private var subscription: HistorySubscription? = null
    private var alarm: HistorySubscription? = null

    fun syncNow() {
        if (running) return
        val address = link.address
        if (address.isNullOrBlank()) {
            status = "Link a strap before syncing."
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
        )
        engine = created
        running = true
        status = "Syncing"
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

    private fun finish(outcome: SessionOutcome) {
        if (!running) return
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
        log("sync closed $outcome")
        onChange()
    }
}
