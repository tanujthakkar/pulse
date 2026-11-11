package app.pulse.link

import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.pulse.sync.AutoSyncOutcome
import app.pulse.sync.AutoSyncTrigger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Fallback history pull when the process is not holding the radio.
 *
 * It calls [AutoSyncCoordinator], which uses the existing link and the
 * existing sync attempt. It does not send command 23 itself.
 *
 * Success means a sync is running, one recently started, or there was nothing
 * to do. A strap that is not ready yet, or a reconnect that has not linked,
 * is [Result.retry]: the old build returned success in that case and the
 * home screen stayed empty.
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
        return autoSyncWorkerResult(coordinator.on(AutoSyncTrigger.WORKER))
    }
}

internal fun autoSyncWorkerResult(outcome: AutoSyncOutcome): Result = when (outcome) {
    AutoSyncOutcome.STARTED,
    AutoSyncOutcome.IN_PROGRESS,
    AutoSyncOutcome.COOLING_DOWN,
    AutoSyncOutcome.IDLE,
    -> Result.success()
    AutoSyncOutcome.RECONNECTING,
    AutoSyncOutcome.NOT_READY,
    -> Result.retry()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoSyncEntryPoint {
    fun coordinator(): AutoSyncCoordinator
}
