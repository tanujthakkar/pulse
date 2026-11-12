package app.pulse.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.pulse.sync.SyncChainReason
import dagger.hilt.android.EntryPointAccessors

/**
 * Restores the five-minute history chain after boot when it is missing.
 *
 * The application start path enqueues the same chain with KEEP. A second
 * KEEP does not add another request.
 */
class SyncChainReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val coordinator = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AutoSyncEntryPoint::class.java,
        ).coordinator()
        coordinator.ensureChain(SyncChainReason.BOOT)
    }
}
