package app.pulse.link

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.pulse.sync.AutoSync
import app.pulse.sync.SyncChainPolicy
import app.pulse.sync.SyncChainRequest
import java.util.concurrent.TimeUnit

/**
 * Enqueues the unique five-minute history chain.
 *
 * [SyncChainPolicy.KEEP] is [ExistingWorkPolicy.KEEP]. [SyncChainPolicy.APPEND]
 * is [ExistingWorkPolicy.APPEND_OR_REPLACE]: the worker that is still running
 * is not cancelled, and its follow-up waits on a success result. A failure
 * result marks that appended request FAILED without running it, so a cooldown
 * returns success instead. [SyncChainPolicy.RESET] is
 * [ExistingWorkPolicy.REPLACE] and is only used when no worker is inside
 * [AutoSyncWorker.doWork], so the five-minute delay restarts from the finish
 * of an in-process sync. There is no network constraint. The old periodic
 * work uses a different name and is cancelled so an upgrade does not keep the
 * twenty-minute job beside this chain.
 *
 * [BackoffPolicy.LINEAR] of [SyncChainRequest.delayMs] applies when the worker
 * returns retry. That is the reschedule for a link that is not ready. A
 * success result appends its own delayed request and does not also retry.
 */
internal object SyncWork {
    fun enqueue(context: Context, request: SyncChainRequest) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(AutoSync.LEGACY_PERIODIC_NAME)
        val work = OneTimeWorkRequestBuilder<AutoSyncWorker>()
            .setInitialDelay(request.delayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, request.delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(request.requiresBatteryNotLow)
                    .build(),
            )
            .build()
        val policy = when (request.policy) {
            SyncChainPolicy.KEEP -> ExistingWorkPolicy.KEEP
            SyncChainPolicy.APPEND -> ExistingWorkPolicy.APPEND_OR_REPLACE
            SyncChainPolicy.RESET -> ExistingWorkPolicy.REPLACE
        }
        manager.enqueueUniqueWork(request.name, policy, work)
    }
}
