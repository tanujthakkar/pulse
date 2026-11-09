package app.pulse.sync

import app.pulse.wire.codec.CommandResult
import app.pulse.wire.codec.HistoryAckCommand
import app.pulse.wire.codec.HistoryAckGate

/**
 * Manual "Sync now" entry.
 *
 * [status] stays a single line for the attempt. [activity] is the live session
 * view: phase, chunk and record counts, saved-row counts, and the outcome of
 * the attempt that just finished. A new attempt clears that outcome.
 *
 * Production leaves [ackEnabled] on [HistoryAckGate.maySend], which stays
 * false. This driver does not open that gate and does not store consent.
 * [armOneShot] records fresh consent for the next attempt only. The engine
 * writes one command 23 after that attempt commits one sendable chunk, while
 * the attempt is still running. There is no send control after the attempt
 * ends. Disconnect and a new process both start from [AckPhase.OFF].
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

    private val gate = Any()
    private var engine: HistorySyncEngine? = null
    private var subscription: HistorySubscription? = null
    private var alarm: HistorySubscription? = null
    private var latest: SyncSnapshot = SyncSnapshot(SyncPhase.IDLE, 0, 0, null)
    private var ackPhase: AckPhase = AckPhase.OFF
    private var armedFirmware: String? = null
    private var claimed = false
    private var ackSamples: Int? = null
    private var ackResult: Int? = null
    private var ackNote: String? = null

    /** Reads local-storage totals into [activity] while no attempt is running. */
    fun refreshSaved() {
        synchronized(gate) {
            if (running) return
            val counts = try {
                store.savedCounts()
            } catch (error: Exception) {
                return
            }
            if (activity.savedSamples == counts.samples && activity.savedChunks == counts.chunks) return
            showLocked(activity.copy(savedSamples = counts.samples, savedChunks = counts.chunks))
        }
    }

    /**
     * Arms one acknowledgement for the next sync.
     *
     * Does not write. [firmware] must be [OneShotAck.FIRMWARE]. A second arm,
     * an arm during an attempt, and any other firmware leave the previous
     * consent unchanged when it is already [AckPhase.ARMED] or [AckPhase.WAITING].
     */
    fun armOneShot(firmware: String?): Boolean {
        synchronized(gate) {
            if (running || ackPhase == AckPhase.ARMED || ackPhase == AckPhase.WAITING) return false
            if (!OneShotAck.allowsFirmware(firmware)) {
                ackNote = "Arm stayed off because hello firmware is not ${OneShotAck.FIRMWARE}."
                showLocked(activity)
                return false
            }
            ackPhase = AckPhase.ARMED
            armedFirmware = firmware
            claimed = false
            ackSamples = null
            ackResult = null
            ackNote = null
            showLocked(activity)
            log("one-shot armed")
            return true
        }
    }

    /**
     * Drops consent before the single write is claimed.
     *
     * Returns false once the write has been claimed. Nothing is sent.
     */
    fun cancelOneShot(): Boolean {
        synchronized(gate) {
            if (claimed) return false
            if (ackPhase != AckPhase.ARMED && ackPhase != AckPhase.WAITING) return false
            ackPhase = AckPhase.OFF
            armedFirmware = null
            ackSamples = null
            ackResult = null
            ackNote = "Acknowledgement cancelled. Nothing was sent."
            showLocked(activity)
            log("one-shot cancelled")
            return true
        }
    }

    /**
     * Drops consent when the strap link drops.
     *
     * An acknowledgement already handed to the link is left visible and is
     * not written again. Consent is not read back from storage.
     */
    fun dropOneShot() {
        synchronized(gate) {
            clearConsentLocked(SessionOutcome.DISCONNECTED)
            showLocked(activity)
        }
    }

    fun syncNow() {
        val created: HistorySyncEngine
        synchronized(gate) {
            if (running) return
            val address = link.address
            if (address.isNullOrBlank()) {
                status = "Link a strap before syncing."
                showLocked(activity.copy(blocked = status, running = false))
                return
            }
            if (ackPhase == AckPhase.ARMED && OneShotAck.allowsFirmware(armedFirmware)) {
                ackPhase = AckPhase.WAITING
                claimed = false
                ackSamples = null
                ackResult = null
                ackNote = null
            } else {
                ackPhase = AckPhase.OFF
                claimed = false
                ackSamples = null
                ackResult = null
                ackNote = null
                armedFirmware = null
            }
            val opened = HistorySyncEngine(
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
                onAckHandoff = { accepted -> onAckHandoff(accepted) },
                onAckResult = { result -> onAckResult(result) },
            )
            engine = opened
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
            showLocked(activity)
            subscription = link.listen(
                onNotification = { source, bytes -> opened.onNotification(source, bytes) },
                onDown = { opened.onDisconnect() },
            )
            created = opened
        }
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
        synchronized(gate) {
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
            showLocked(activity)
        }
    }

    /**
     * Claims the single write for this attempt, or refuses it.
     *
     * Called only after the engine has committed the chunk. The claim is set
     * before this returns so a re-entered end cannot take a second write.
     * A second token, a token that fails [HistoryAckCommand.sendable], or a
     * row that cannot be read back consumes the consent and writes nothing.
     */
    private fun noteDurable(chunk: DurableChunk): Boolean {
        synchronized(gate) {
            if (ackPhase != AckPhase.WAITING || claimed) return false
            claimed = true
            ackPhase = AckPhase.WRITING
            if (!OneShotAck.allowsFirmware(armedFirmware)) {
                refuse("Hello firmware is not ${OneShotAck.FIRMWARE}. Nothing was sent.")
                return false
            }
            if (!HistoryAckCommand.sendable(chunk.token)) {
                refuse("This token is not acknowledged. Nothing was sent.")
                return false
            }
            if (link.address.isNullOrBlank()) {
                refuse("The strap link is down. Nothing was sent.")
                return false
            }
            val stored = try {
                store.persistedChunk(chunk.sessionId, chunk.token)
            } catch (error: Exception) {
                null
            }
            if (stored == null ||
                stored.chunkId != chunk.chunkId ||
                stored.sampleCount != chunk.sampleCount
            ) {
                refuse("The saved chunk could not be verified. Nothing was sent.")
                return false
            }
            ackSamples = stored.sampleCount
            showLocked(activity)
            return true
        }
    }

    private fun onAckHandoff(accepted: Boolean) {
        synchronized(gate) {
            if (!claimed) return
            armedFirmware = null
            if (!accepted) {
                ackPhase = AckPhase.RADIO_REFUSED
                ackNote = null
                showLocked(activity)
                log("one-shot radio refused")
                return
            }
            ackPhase = if (running) AckPhase.HANDED_OFF else AckPhase.NO_RESPONSE
            ackNote = null
            showLocked(activity)
            log("one-shot handed to the radio")
        }
    }

    private fun onAckResult(result: Int) {
        synchronized(gate) {
            if (ackPhase != AckPhase.HANDED_OFF &&
                ackPhase != AckPhase.PENDING &&
                ackPhase != AckPhase.WRITING
            ) {
                return
            }
            ackResult = result
            ackNote = null
            ackPhase = if (result == CommandResult.PENDING) AckPhase.PENDING else AckPhase.RESULT
            showLocked(activity)
            log("one-shot result $result")
        }
    }

    private fun refuse(reason: String) {
        ackPhase = AckPhase.REFUSED
        armedFirmware = null
        ackNote = reason
        showLocked(activity)
        log("one-shot refused")
    }

    private fun clearConsentLocked(outcome: SessionOutcome) {
        when (ackPhase) {
            AckPhase.ARMED, AckPhase.WAITING -> if (!claimed) {
                ackPhase = AckPhase.OFF
                armedFirmware = null
                ackSamples = null
                ackResult = null
                ackNote = if (outcome == SessionOutcome.DISCONNECTED) {
                    "The link dropped. Nothing was sent."
                } else {
                    "The attempt ended before a saved chunk was ready. Nothing was sent."
                }
            }
            AckPhase.HANDED_OFF -> {
                ackPhase = AckPhase.NO_RESPONSE
                armedFirmware = null
                ackNote = if (outcome == SessionOutcome.DISCONNECTED) {
                    "The link dropped before a strap result. Passing bytes to the radio is not a strap result."
                } else {
                    null
                }
            }
            AckPhase.PENDING -> {
                ackPhase = AckPhase.NO_RESPONSE
                armedFirmware = null
                ackNote = if (outcome == SessionOutcome.DISCONNECTED) {
                    "The link dropped while the strap result was pending. That is not success."
                } else {
                    "The strap result stayed pending when the attempt ended. That is not success."
                }
            }
            else -> Unit
        }
    }

    private fun showLocked(next: SyncActivity) {
        activity = next.copy(
            ackPhase = ackPhase,
            ackSamples = ackSamples,
            ackResult = ackResult,
            ackNote = ackNote,
        )
        onChange()
    }

    private fun finish(outcome: SessionOutcome) {
        synchronized(gate) {
            if (!running) return
            val snap = latest
            running = false
            clearConsentLocked(outcome)
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
            showLocked(activity)
            log("sync closed $outcome")
        }
    }
}
