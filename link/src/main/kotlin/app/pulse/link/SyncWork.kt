package app.pulse.link

import android.content.Context
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
 * [SyncChainPolicy.KEEP] is [ExistingWorkPolicy.KEEP]. [SyncChainPolicy.REPLACE]
 * is [ExistingWorkPolicy.APPEND_OR_REPLACE]: one follow-up, and the worker that
 * is still running is not cancelled before it can report its result.
 * [ExistingWorkPolicy.REPLACE] would cancel that worker. There is no network
 * constraint. The old periodic work uses a different name and is cancelled so
 * an upgrade does not keep the twenty-minute job beside this chain.
 */
internal object SyncWork {
    fun enqueue(context: Context, request: SyncChainRequest) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(AutoSync.LEGACY_PERIODIC_NAME)
        val work = OneTimeWorkRequestBuilder<AutoSyncWorker>()
            .setInitialDelay(request.delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(request.requiresBatteryNotLow)
                    .build(),
            )
            .build()
        val policy = when (request.policy) {
            SyncChainPolicy.KEEP -> ExistingWorkPolicy.KEEP
            SyncChainPolicy.REPLACE -> ExistingWorkPolicy.APPEND_OR_REPLACE
        }
        manager.enqueueUniqueWork(request.name, policy, work)
    }
}
