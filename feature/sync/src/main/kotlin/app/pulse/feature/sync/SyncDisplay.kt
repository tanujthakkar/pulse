package app.pulse.feature.sync

import app.pulse.sync.AckPhase
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
    val canCancel: Boolean,
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
        ackWarning = ackWarning(),
        ackPreview = ackPreview(activity),
        ackStatus = ackStatus(activity),
        canArm = !activity.running &&
            activity.ackPhase != AckPhase.ARMED &&
            activity.ackPhase != AckPhase.WAITING &&
            activity.ackPhase != AckPhase.WRITING,
        canCancel = activity.ackPhase == AckPhase.ARMED || activity.ackPhase == AckPhase.WAITING,
    )
}

private fun ackWarning(): String {
    return "A successful acknowledgement can let the strap reclaim that history. " +
        "Arm this before Sync now. One armed sync sends a single acknowledgement after that chunk is saved, " +
        "while the strap is still waiting, and then turns off. Hello firmware must be ${OneShotAck.FIRMWARE}."
}

private fun ackStatus(activity: SyncActivity): String {
    activity.ackNote?.let { return it }
    return when (activity.ackPhase) {
        AckPhase.OFF -> "History acknowledgement is off. Sync does not send it."
        AckPhase.ARMED -> "Armed for the next sync. Nothing has been sent."
        AckPhase.WAITING -> "Armed for this sync. Waiting for one saved chunk. Nothing has been sent."
        AckPhase.WRITING -> "One acknowledgement is being written. It will not be written twice."
        AckPhase.HANDED_OFF -> "The acknowledgement was passed to the radio. That is not a strap response."
        AckPhase.RADIO_REFUSED -> "The radio did not take the acknowledgement. It will not be retried."
        AckPhase.PENDING -> "The strap result is pending. That is not success."
        AckPhase.RESULT -> resultSentence(activity.ackResult)
        AckPhase.NO_RESPONSE ->
            "No command response arrived before the attempt ended. Passing bytes to the radio is not a strap result."
        AckPhase.REFUSED -> "Nothing was sent."
    }
}

private fun resultSentence(result: Int?): String = when (result) {
    0 -> "The strap result was 0. The boundary was not updated."
    1 -> "The strap result was 1 at the response boundary. That can let the strap reclaim that history. " +
        "It does not prove the reclaim finished."
    2 -> "The strap result is pending. That is not success."
    3 -> "The strap result was 3, unsupported."
    null -> "A strap result was observed without a code. It is not treated as success."
    else -> "The strap result was $result. It is not treated as success."
}

private fun ackPreview(activity: SyncActivity): String? {
    val samples = activity.ackSamples ?: return null
    return "Layout-18 rows in the saved chunk: $samples."
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
