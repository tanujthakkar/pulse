package app.pulse.sync

/**
 * When an automatic history pull may start.
 *
 * The decision does not write a strap frame. Command 23 is still sent only
 * by the history engine, after a chunk is saved, and only while the user
 * switch is on.
 */
object AutoSync {
    /**
     * Gap between history pulls while the strap stays connected.
     *
     * Five minutes is shorter than WorkManager's periodic minimum, so the
     * fallback is a self-rescheduling one-time request delayed by this long.
     * Doze and a low battery can still run that request later.
     */
    const val INTERVAL_MS: Long = 5L * 60L * 1000L

    /** Unique name of the one-time chain. Not the old periodic work. */
    const val WORK_NAME: String = "pulse-history-chain"

    /** Periodic work from builds before the five-minute chain. Cancelled on enqueue. */
    const val LEGACY_PERIODIC_NAME: String = "pulse-history-sync"

    /** Connect and a foreground open in the same minute share one pull. */
    const val FOREGROUND_COOLDOWN_MS: Long = 60_000L

    /**
     * How soon a worker or the in-process timer may start another pull after
     * the previous attempt finished.
     *
     * Shorter than [INTERVAL_MS]. The chain delay itself stays [INTERVAL_MS],
     * measured from the finish. The in-process timer no longer starts a pull
     * on this window.
     */
    const val CHAIN_COOLDOWN_MS: Long = 60_000L

    /**
     * How long the fallback worker may block while one history attempt runs.
     *
     * A normal simulated night is longer than a minute. The worker used to
     * return success as soon as the attempt started, before any history frame.
     */
    const val WORKER_WAIT_MS: Long = 10L * 60L * 1000L
}

enum class AutoSyncTrigger {
    CONNECTED,
    FOREGROUND,
    PERIODIC,
    WORKER,
}

enum class AutoSyncAction {
    SYNC,
    RECONNECT,
    NONE,
}

/**
 * What [app.pulse.link.AutoSyncCoordinator.on] did with one trigger.
 *
 * [NOT_READY] is a sync that was asked for and did not write. [IDLE] is a
 * trigger with nothing to do. A worker must not report success for [NOT_READY].
 */
enum class AutoSyncOutcome {
    STARTED,
    IN_PROGRESS,
    COOLING_DOWN,
    RECONNECTING,
    NOT_READY,
    IDLE,
    /** The history attempt this worker waited for saw metadata complete. */
    FINISHED,
    /** The attempt ended another way, or the wait timed out. */
    FAILED,
}

data class AutoSyncInput(
    val trigger: AutoSyncTrigger,
    val linked: Boolean,
    val running: Boolean,
    val millisSinceLastStart: Long?,
    val millisSinceLastFinish: Long?,
    val phaseIdle: Boolean,
    val phaseFailed: Boolean,
    val knownDevice: Boolean,
)

/**
 * [AutoSyncAction.SYNC] means call the existing history attempt.
 * [AutoSyncAction.RECONNECT] means start or retry the existing link.
 * A scan starts only when a device row is already stored and the link is
 * idle or failed, so a phone that has never connected does not scan.
 */
fun autoSyncAction(input: AutoSyncInput): AutoSyncAction {
    if (input.running) return AutoSyncAction.NONE
    if (input.linked) {
        val elapsed = input.millisSinceLastStart
        return when (input.trigger) {
            AutoSyncTrigger.CONNECTED -> AutoSyncAction.SYNC
            AutoSyncTrigger.FOREGROUND ->
                if (elapsed == null || elapsed >= AutoSync.FOREGROUND_COOLDOWN_MS) {
                    AutoSyncAction.SYNC
                } else {
                    AutoSyncAction.NONE
                }
            AutoSyncTrigger.PERIODIC -> AutoSyncAction.NONE
            AutoSyncTrigger.WORKER -> {
                val sinceFinish = input.millisSinceLastFinish
                if (sinceFinish == null || sinceFinish >= AutoSync.CHAIN_COOLDOWN_MS) {
                    AutoSyncAction.SYNC
                } else {
                    AutoSyncAction.NONE
                }
            }
        }
    }
    val canReconnect = input.knownDevice && (input.phaseIdle || input.phaseFailed)
    if (!canReconnect) return AutoSyncAction.NONE
    return when (input.trigger) {
        AutoSyncTrigger.WORKER, AutoSyncTrigger.FOREGROUND -> AutoSyncAction.RECONNECT
        else -> AutoSyncAction.NONE
    }
}
