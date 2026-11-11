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
import app.pulse.sync.AutoSyncOutcome
import app.pulse.sync.AutoSyncTrigger
import app.pulse.sync.autoSyncAction
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
    @Volatile private var knownDevice: Boolean = false
    private val deviceReads = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pulse-device-read").apply { isDaemon = true }
    }

    /** How long [runWorker] waits for a link that is still coming up, then for the sync. */
    @Volatile
    internal var workerWaitMs: Long = AutoSync.WORKER_WAIT_MS

    fun start() {
        if (started) return
        started = true
        armLinkWatch()
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

    /**
     * Watches the link without scheduling WorkManager.
     *
     * History is ready only after hello succeeds. [LinkPhase.LINKED] is earlier,
     * before the command port has an address, and a start recorded at that
     * moment never writes a clock get.
     */
    internal fun armLinkWatch() {
        if (linkWatchInstalled) return
        linkWatchInstalled = true
        link.watch { snapshot ->
            val linked = readyForHistory(snapshot)
            if (linked && !wasLinked) on(AutoSyncTrigger.CONNECTED)
            wasLinked = linked
        }
    }

    /**
     * @return what this trigger actually did. [AutoSyncOutcome.NOT_READY] means
     * the policy asked for a sync and [HistorySyncHost.syncNow] wrote nothing.
     * That used to be recorded as a start, so later workers returned success
     * during the cooldown without ever syncing.
     */
    /**
     * Runs the worker trigger and, when that starts or joins a history attempt,
     * waits until the attempt finishes.
     *
     * A link that is still scanning, bonding, connecting, or waiting on hello
     * is not success. The worker waits for that link, then for the history
     * attempt. The result is not [AutoSyncOutcome.STARTED]. Callers that only
     * need to kick a sync, such as a connect, still use [on].
     */
    fun runWorker(): AutoSyncOutcome {
        val deadline = clock() + workerWaitMs
        while (true) {
            when (val outcome = on(AutoSyncTrigger.WORKER)) {
                AutoSyncOutcome.STARTED,
                AutoSyncOutcome.IN_PROGRESS,
                -> {
                    val left = (deadline - clock()).coerceAtLeast(1L)
                    return settleWorkerSync(
                        running = { sync.running.value },
                        outcome = { sync.activity.value.outcome },
                        watch = { listener -> sync.watch(listener) },
                        timeoutMs = left,
                    )
                }
                AutoSyncOutcome.COOLING_DOWN,
                AutoSyncOutcome.FINISHED,
                -> return outcome
                AutoSyncOutcome.IDLE,
                AutoSyncOutcome.NOT_READY,
                AutoSyncOutcome.RECONNECTING,
                AutoSyncOutcome.FAILED,
                -> {
                    if (!linkIsComingUp()) return outcome
                    if (!waitForLink(deadline)) return AutoSyncOutcome.NOT_READY
                }
            }
        }
    }

    fun on(trigger: AutoSyncTrigger): AutoSyncOutcome {
        return when (autoSyncAction(current(trigger))) {
            AutoSyncAction.SYNC -> {
                if (sync.syncNow()) {
                    lastStartMs = clock()
                    AutoSyncOutcome.STARTED
                } else if (sync.running.value) {
                    AutoSyncOutcome.IN_PROGRESS
                } else {
                    AutoSyncOutcome.NOT_READY
                }
            }
            AutoSyncAction.RECONNECT -> {
                reconnect()
                AutoSyncOutcome.RECONNECTING
            }
            AutoSyncAction.NONE -> when {
                sync.running.value -> AutoSyncOutcome.IN_PROGRESS
                lastStartMs != null && readyForHistory(link.snapshot()) -> AutoSyncOutcome.COOLING_DOWN
                else -> AutoSyncOutcome.IDLE
            }
        }
    }

    private var linkWatchInstalled = false

    private fun readyForHistory(snapshot: LinkSnapshot): Boolean =
        snapshot.phase == LinkPhase.LINKED && snapshot.hello == HelloProgress.SUCCEEDED

    private fun linkIsComingUp(): Boolean = when (link.snapshot().phase) {
        LinkPhase.SCANNING,
        LinkPhase.BONDING,
        LinkPhase.CONNECTING,
        LinkPhase.LINKED,
        -> true
        LinkPhase.IDLE,
        LinkPhase.PAIRING_HELP,
        LinkPhase.FAILED,
        -> false
    }

    /**
     * Blocks until the link leaves the connecting phases, a sync is running, or [deadlineMs].
     *
     * The current snapshot is delivered immediately and does not count as a change
     * while the link is still coming up.
     */
    private fun waitForLink(deadlineMs: Long): Boolean {
        val left = deadlineMs - clock()
        if (left <= 0L) return false
        if (readyForHistory(link.snapshot()) || sync.running.value) return false
        val latch = CountDownLatch(1)
        val onLink: (LinkSnapshot) -> Unit = { snapshot ->
            val phase = snapshot.phase
            val settled = readyForHistory(snapshot) ||
                phase == LinkPhase.FAILED ||
                phase == LinkPhase.IDLE ||
                phase == LinkPhase.PAIRING_HELP
            if (settled) latch.countDown()
        }
        link.watch(onLink)
        val removeSync = sync.watch {
            if (sync.running.value) latch.countDown()
        }
        return try {
            latch.await(left, TimeUnit.MILLISECONDS)
        } finally {
            link.unwatch(onLink)
            removeSync()
        }
    }

    private fun current(trigger: AutoSyncTrigger): AutoSyncInput {
        val snapshot = link.snapshot()
        val phase = snapshot.phase
        val elapsed = lastStartMs?.let { clock() - it }
        return AutoSyncInput(
            trigger = trigger,
            linked = readyForHistory(snapshot),
            running = sync.running.value,
            millisSinceLastStart = elapsed,
            phaseIdle = phase == LinkPhase.IDLE,
            phaseFailed = phase == LinkPhase.FAILED,
            knownDevice = knownDevice(),
        )
    }

    /**
     * A worker thread reads the device row. The main thread uses the last
     * value and refreshes it in the background, so a reconnect decision does
     * not open the database on the main thread.
     */
    private fun knownDevice(): Boolean {
        val main = Looper.myLooper() == Looper.getMainLooper()
        if (!main) {
            val stored = devices.latest() != null
            knownDevice = stored
            return stored
        }
        deviceReads.execute {
            knownDevice = devices.latest() != null
        }
        return knownDevice
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
