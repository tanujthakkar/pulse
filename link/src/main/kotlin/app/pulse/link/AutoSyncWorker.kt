package app.pulse.link

import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker.Result
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.pulse.ble.BleClients
import app.pulse.sync.AutoSync
import app.pulse.sync.AutoSyncOutcome
import app.pulse.sync.SessionOutcome
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Fallback history pull when the process is not holding the radio.
 *
 * It calls [AutoSyncCoordinator], which uses the existing link and the
 * existing sync attempt. It does not send command 23 itself.
 *
 * Success is a finished history attempt, a recent one still inside the
 * cooldown, or nothing to do. Starting an attempt is not success: the worker
 * waits for that attempt and only then reports the outcome. A strap that is
 * not ready, a reconnect, or an attempt that did not complete is [Result.retry].
 */
class AutoSyncWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        val coordinator = EntryPointAccessors.fromApplication(
            applicationContext,
            AutoSyncEntryPoint::class.java,
        ).coordinator()
        val outcome = coordinator.runWorker()
        Log.i(BleClients.LOG_TAG, "auto-sync worker $outcome")
        return autoSyncWorkerResult(outcome)
    }
}

internal fun autoSyncWorkerResult(outcome: AutoSyncOutcome): Result = when (outcome) {
    AutoSyncOutcome.FINISHED,
    AutoSyncOutcome.COOLING_DOWN,
    AutoSyncOutcome.IDLE,
    -> Result.success()
    AutoSyncOutcome.STARTED,
    AutoSyncOutcome.IN_PROGRESS,
    AutoSyncOutcome.RECONNECTING,
    AutoSyncOutcome.NOT_READY,
    AutoSyncOutcome.FAILED,
    -> Result.retry()
}

/**
 * Blocks until the history attempt that is already running reaches an outcome.
 *
 * [watch] is invoked immediately with the current state, the same way
 * [HistorySyncHost.watch] does. A timeout is [AutoSyncOutcome.FAILED], not success.
 */
internal fun settleWorkerSync(
    running: () -> Boolean,
    outcome: () -> SessionOutcome?,
    watch: (listener: () -> Unit) -> () -> Unit,
    timeoutMs: Long = AutoSync.WORKER_WAIT_MS,
    await: (CountDownLatch, Long) -> Boolean = { latch, wait -> latch.await(wait, TimeUnit.MILLISECONDS) },
): AutoSyncOutcome {
    val latch = CountDownLatch(1)
    val remove = watch {
        if (!running() && outcome() != null) latch.countDown()
    }
    val finished = try {
        await(latch, timeoutMs)
    } finally {
        remove()
    }
    if (!finished || outcome() != SessionOutcome.COMPLETE) return AutoSyncOutcome.FAILED
    return AutoSyncOutcome.FINISHED
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoSyncEntryPoint {
    fun coordinator(): AutoSyncCoordinator
}
