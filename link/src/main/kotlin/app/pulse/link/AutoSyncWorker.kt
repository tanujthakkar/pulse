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
 * Success is only a history attempt that reached metadata complete. A sync
 * that is already running is skipped. Every run, completed or failed, asks
 * the coordinator to schedule the next one-time request. The worker does not
 * use [Result.retry], which would stack a backoff on that chain.
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
        val outcome = try {
            coordinator.runWorker()
        } catch (error: Exception) {
            Log.w(BleClients.LOG_TAG, "auto-sync worker failed", error)
            AutoSyncOutcome.FAILED
        } finally {
            try {
                coordinator.scheduleAfterRun()
            } catch (error: Exception) {
                Log.w(BleClients.LOG_TAG, "auto-sync reschedule failed", error)
            }
        }
        Log.i(BleClients.LOG_TAG, "auto-sync worker $outcome")
        return autoSyncWorkerResult(outcome)
    }
}

internal fun autoSyncWorkerResult(outcome: AutoSyncOutcome): Result =
    if (outcome == AutoSyncOutcome.FINISHED) Result.success() else Result.failure()

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
