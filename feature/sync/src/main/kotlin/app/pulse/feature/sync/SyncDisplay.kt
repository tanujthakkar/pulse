package app.pulse.feature.sync

import app.pulse.sync.HistoryLimits
import app.pulse.sync.PhoneTime
import app.pulse.sync.SessionOutcome
import app.pulse.sync.SyncActivity
import app.pulse.sync.SyncPhase

/** Screen copy for one [SyncActivity]. The driver stays free of these sentences. */
data class SyncUi(
    val state: String,
    val chunks: String,
    val records: String,
    val outcome: String,
    val outcomeIsError: Boolean,
    val time: String,
    val notice: String?,
    val canSync: Boolean,
)

fun syncUi(activity: SyncActivity): SyncUi {
    val outcome = activity.outcome
    val failed = outcome != null && outcome != SessionOutcome.COMPLETE
    return SyncUi(
        state = phaseLabel(activity.phase),
        chunks = activity.chunks.toString(),
        records = activity.records.toString(),
        outcome = outcome?.let(::outcomeMessage) ?: IDLE_MARK,
        outcomeIsError = failed && !activity.running,
        time = formatSyncTime(activity.finishedAt),
        notice = activity.blocked,
        canSync = !activity.running,
    )
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

/** One sentence for every [SessionOutcome] the engine can close with. */
fun outcomeMessage(outcome: SessionOutcome): String = when (outcome) {
    SessionOutcome.COMPLETE -> "History finished."
    SessionOutcome.ABORTED -> "The history transfer stopped before it finished."
    SessionOutcome.DISCONNECTED -> "The link dropped before history finished."
    SessionOutcome.WATCHDOG ->
        "No history arrived for ${HistoryLimits.WATCHDOG_MS / 1000} seconds, so the open chunk was dropped."
    SessionOutcome.BOUNDED ->
        "History reached ${HistoryLimits.MAX_RECORDS_WITHOUT_END} records without an end, so the open chunk was dropped."
}

fun formatSyncTime(time: PhoneTime?): String {
    if (time == null) return IDLE_MARK
    return "${time.unixSeconds} s, ${time.ticks} ticks"
}

private const val IDLE_MARK: String = "—"
