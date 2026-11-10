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
     * Twenty minutes sits inside the 15–30 minute range. WorkManager's
     * periodic work cannot be shorter than 15 minutes, and Android may run
     * that fallback later than this.
     */
    const val INTERVAL_MS: Long = 20L * 60L * 1000L

    /** Connect and a foreground open in the same minute share one pull. */
    const val FOREGROUND_COOLDOWN_MS: Long = 60_000L

    const val WORK_NAME: String = "pulse-history-sync"
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

data class AutoSyncInput(
    val trigger: AutoSyncTrigger,
    val linked: Boolean,
    val running: Boolean,
    val millisSinceLastStart: Long?,
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
            AutoSyncTrigger.PERIODIC, AutoSyncTrigger.WORKER ->
                if (elapsed == null || elapsed >= AutoSync.INTERVAL_MS) {
                    AutoSyncAction.SYNC
                } else {
                    AutoSyncAction.NONE
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
