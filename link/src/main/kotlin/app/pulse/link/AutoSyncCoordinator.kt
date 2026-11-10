package app.pulse.link

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.pulse.data.DeviceDirectory
import app.pulse.sync.AutoSync
import app.pulse.sync.AutoSyncAction
import app.pulse.sync.AutoSyncInput
import app.pulse.sync.AutoSyncTrigger
import app.pulse.sync.autoSyncAction
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts history pulls on the existing sync path.
 *
 * A connect, a foreground open, and a [AutoSync.INTERVAL_MS] timer each ask
 * [HistorySyncHost.syncNow]. That attempt still sends command 23 only after
 * a chunk is saved, and only while the user switch is on. This class does
 * not write that switch or send its own strap command.
 *
 * The foreground service keeps the radio up and reconnects a drop on its
 * own. WorkManager is the fallback after the process is gone. It does not
 * scan unless a device row is already stored, and it waits while the battery
 * is low. Android may defer that work.
 */
@Singleton
class AutoSyncCoordinator @Inject constructor(
    private val link: StrapLink,
    private val sync: HistorySyncHost,
    private val devices: DeviceDirectory,
    @ApplicationContext private val context: Context,
) {
    private val clock: () -> Long = { System.currentTimeMillis() }
    private val handler = Handler(Looper.getMainLooper())
    private val periodic = object : Runnable {
        override fun run() {
            on(AutoSyncTrigger.PERIODIC)
            handler.postDelayed(this, AutoSync.INTERVAL_MS)
        }
    }
    private var started = false
    private var wasLinked = false
    private var lastStartMs: Long? = null

    fun start() {
        if (started) return
        started = true
        link.watch { snapshot ->
            val linked = snapshot.phase == LinkPhase.LINKED
            if (linked && !wasLinked) on(AutoSyncTrigger.CONNECTED)
            wasLinked = linked
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    on(AutoSyncTrigger.FOREGROUND)
                }
            },
        )
        handler.postDelayed(periodic, AutoSync.INTERVAL_MS)
        scheduleFallback()
    }

    fun on(trigger: AutoSyncTrigger) {
        when (autoSyncAction(current(trigger))) {
            AutoSyncAction.SYNC -> {
                lastStartMs = clock()
                sync.syncNow()
            }
            AutoSyncAction.RECONNECT -> reconnect()
            AutoSyncAction.NONE -> Unit
        }
    }

    private fun current(trigger: AutoSyncTrigger): AutoSyncInput {
        val phase = link.snapshot().phase
        val elapsed = lastStartMs?.let { clock() - it }
        return AutoSyncInput(
            trigger = trigger,
            linked = phase == LinkPhase.LINKED,
            running = sync.running.value,
            millisSinceLastStart = elapsed,
            phaseIdle = phase == LinkPhase.IDLE,
            phaseFailed = phase == LinkPhase.FAILED,
            knownDevice = devices.latest() != null,
        )
    }

    private fun reconnect() {
        when (link.snapshot().phase) {
            LinkPhase.IDLE -> link.start()
            LinkPhase.FAILED -> link.retry()
            else -> Unit
        }
    }

    private fun scheduleFallback() {
        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(AutoSync.INTERVAL_MS, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            AutoSync.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
