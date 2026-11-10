package app.pulse.link

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
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
        coordinator.on(AutoSyncTrigger.WORKER)
        return Result.success()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoSyncEntryPoint {
    fun coordinator(): AutoSyncCoordinator
}
