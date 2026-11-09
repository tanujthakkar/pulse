package app.pulse.feature.sync

import app.pulse.sync.HistoryLimits
import app.pulse.sync.OneShotAck
import app.pulse.sync.PhoneTime
import app.pulse.sync.SessionOutcome
import app.pulse.sync.SyncActivity
import app.pulse.sync.SyncPhase

/** Screen copy for one [SyncActivity]. The driver stays free of these sentences. */
data class SyncUi(
    val state: String,
    val chunks: String,
    val records: String,
    val savedRecords: String,
    val savedChunks: String,
    val outcome: String,
    val outcomeIsError: Boolean,
    val time: String,
    val notice: String?,
    val canSync: Boolean,
    val ackWarning: String,
    val ackPreview: String?,
    val ackStatus: String?,
    val canArm: Boolean,
    val canSend: Boolean,
)

fun syncUi(activity: SyncActivity): SyncUi {
    val outcome = if (activity.running) null else activity.outcome
    val failed = outcome != null && outcome != SessionOutcome.COMPLETE
    return SyncUi(
        state = phaseLabel(activity.phase),
        chunks = chunkLine(activity),
        records = activity.records.toString(),
        savedRecords = activity.savedSamples.toString(),
        savedChunks = activity.savedChunks.toString(),
        outcome = outcome?.let {
            outcomeMessage(it, activity.chunks, activity.droppedUncommitted)
        } ?: IDLE_MARK,
        outcomeIsError = failed,
        time = if (activity.running) IDLE_MARK else formatSyncTime(activity.finishedAt),
        notice = activity.blocked,
        canSync = !activity.running,
        ackWarning = ackWarning(activity),
        ackPreview = ackPreview(activity),
        ackStatus = activity.ackNote,
        canArm = activity.ackSamples != null && !activity.ackArmed && !activity.running,
        canSend = activity.ackArmed && activity.ackSamples != null && !activity.running,
    )
}

private fun ackWarning(activity: SyncActivity): String {
    if (activity.ackSamples == null) {
        return "History acknowledgement is off. Sync does not send it."
    }
    return "A successful acknowledgement can let the strap reclaim that history. " +
        "Arming does not send. Send is one command, then off, and only for hello firmware " +
        "${OneShotAck.FIRMWARE}."
}

private fun ackPreview(activity: SyncActivity): String? {
    val samples = activity.ackSamples ?: return null
    val state = if (activity.ackArmed) "Armed." else "Not armed."
    return "$state Saved chunk waiting. Layout-18 rows in that chunk: $samples."
}

/** End-token counts for this attempt. New means stored for the first time. */
fun chunkLine(activity: SyncActivity): String {
    return "Received ${activity.endsReceived}. New ${activity.chunks}. " +
        "Already stored ${activity.chunksDuplicate}. Retried ${activity.endsRetried}. " +
        "Rejected ${activity.endsRejected}. Ignored completes ${activity.ignoredCompletes}."
}

fun phaseLabel(phase: SyncPhase): String = when (phase) {
    SyncPhase.IDLE -> "Idle"
    SyncPhase.CLOCK -> "Reading clock"
    SyncPhase.CLOCK_SET -> "Setting clock"
    SyncPhase.CLOCK_VERIFY -> "Checking clock"
    SyncPhase.RANGE -> "Reading data range"
    SyncPhase.START -> "Starting history"
    SyncPhase.STREAM -> "Receiving history"
    SyncPhase.DONE -> "Finished"
}

/**
 * One sentence for every [SessionOutcome] the engine can close with.
 *
 * [committedChunks] and [droppedUncommitted] keep a timeout from saying that
 * stored rows were dropped. Stored rows stay either way.
 */
fun outcomeMessage(
    outcome: SessionOutcome,
    committedChunks: Int = 0,
    droppedUncommitted: Boolean = false,
): String = when (outcome) {
    SessionOutcome.COMPLETE -> if (droppedUncommitted) {
        "History finished. The uncommitted records were not saved."
    } else {
        "History finished."
    }
    SessionOutcome.ABORTED -> "The history transfer stopped before it finished."
    SessionOutcome.DISCONNECTED -> "The link dropped before history finished."
    SessionOutcome.WATCHDOG -> timeoutMessage(
        "No history arrived for ${HistoryLimits.WATCHDOG_MS / 1000} seconds.",
        committedChunks,
        droppedUncommitted,
    )
    SessionOutcome.BOUNDED -> timeoutMessage(
        "History reached ${HistoryLimits.MAX_RECORDS_WITHOUT_END} records without an end.",
        committedChunks,
        droppedUncommitted,
    )
}

private fun timeoutMessage(
    lead: String,
    committedChunks: Int,
    droppedUncommitted: Boolean,
): String {
    val saved = if (committedChunks > 0) {
        "Saved chunks from this attempt stay stored."
    } else {
        "Nothing from this attempt was saved."
    }
    val tail = if (droppedUncommitted) {
        "The uncommitted records were dropped."
    } else {
        "No uncommitted records were dropped."
    }
    return "$lead $saved $tail"
}

fun formatSyncTime(time: PhoneTime?): String {
    if (time == null) return IDLE_MARK
    return "${time.unixSeconds} s, ${time.ticks} ticks"
}

private const val IDLE_MARK: String = "—"
