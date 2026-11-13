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
 * A finished pull, a cooldown, a skip, and an idle run return success and
 * append the next request. That request's five-minute delay starts when this
 * run returns, which is when the previous run finishes. [Result.failure]
 * would mark an APPEND_OR_REPLACE follow-up FAILED before it is enqueued, so
 * a cooldown does not use it. A link that is not ready returns [Result.retry];
 * the linear backoff is the reschedule, and no second request is appended.
 */
class AutoSyncWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        val coordinator = coordinatorForTest ?: EntryPointAccessors.fromApplication(
            applicationContext,
            AutoSyncEntryPoint::class.java,
        ).coordinator()
        coordinator.beginWorker()
        try {
            val outcome = try {
                coordinator.runWorker()
            } catch (error: Exception) {
                Log.w(BleClients.LOG_TAG, "auto-sync worker failed", error)
                AutoSyncOutcome.FAILED
            }
            if (autoSyncKeepsTheChain(outcome)) {
                try {
                    coordinator.scheduleAfterRun()
                } catch (error: Exception) {
                    Log.w(BleClients.LOG_TAG, "auto-sync reschedule failed", error)
                }
            }
            Log.i(BleClients.LOG_TAG, "auto-sync worker $outcome")
            return autoSyncWorkerResult(outcome)
        } finally {
            coordinator.endWorker()
        }
    }

    internal companion object {
        /** Set by the chain test so WorkManager can run without the Hilt graph. */
        @Volatile
        var coordinatorForTest: AutoSyncCoordinator? = null
    }
}

/**
 * True when this outcome should append the next delayed request.
 *
 * [AutoSyncOutcome.FINISHED], [AutoSyncOutcome.COOLING_DOWN],
 * [AutoSyncOutcome.IN_PROGRESS], and [AutoSyncOutcome.IDLE] keep the chain.
 * The others return retry and rely on the request's backoff.
 */
internal fun autoSyncKeepsTheChain(outcome: AutoSyncOutcome): Boolean = when (outcome) {
    AutoSyncOutcome.FINISHED,
    AutoSyncOutcome.COOLING_DOWN,
    AutoSyncOutcome.IN_PROGRESS,
    AutoSyncOutcome.IDLE,
    -> true
    AutoSyncOutcome.FAILED,
    AutoSyncOutcome.NOT_READY,
    AutoSyncOutcome.RECONNECTING,
    AutoSyncOutcome.STARTED,
    -> false
}

internal fun autoSyncWorkerResult(outcome: AutoSyncOutcome): Result =
    if (autoSyncKeepsTheChain(outcome)) Result.success() else Result.retry()

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
