package app.pulse.link

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.pulse.ble.BleClients
import app.pulse.data.DeviceDirectory
import app.pulse.sync.AutoSync
import app.pulse.sync.AutoSyncAction
import app.pulse.sync.AutoSyncInput
import app.pulse.sync.AutoSyncOutcome
import app.pulse.sync.AutoSyncTrigger
import app.pulse.sync.SyncChainReason
import app.pulse.sync.autoSyncAction
import app.pulse.sync.syncChainRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts history pulls on the existing sync path.
 *
 * A connect and a foreground open ask [HistorySyncHost.syncNow]. The
 * five-minute gap is only the WorkManager chain. An in-process timer used to
 * start another pull on the main thread, and the close of that pull replaced
 * the pending chain request before the worker could run. That timer is gone.
 * The attempt still sends command 23 only after a chunk is saved, and only
 * while the user switch is on. This class does not write that switch or send
 * its own strap command.
 *
 * The foreground service keeps the radio up and reconnects a drop on its
 * own. The chain is not a second foreground service. It does not scan unless
 * a device row is already stored, and it waits while the battery is low.
 * Doze may run that chain later.
 */
@Singleton
class AutoSyncCoordinator @Inject constructor(
    private val link: StrapLink,
    private val sync: HistorySyncHost,
    private val devices: DeviceDirectory,
    @ApplicationContext private val context: Context,
) {
    private val clock: () -> Long = { System.currentTimeMillis() }
    private var started = false
    private var wasLinked = false
    private var lastStartMs: Long? = null
    private var lastFinishMs: Long? = null
    private var sawRunning = false
    private var finishWatchInstalled = false
    /** The launch pull has already moved the chain's delay to a finish. */
    private var retimed = false
    @Volatile private var workerDepth: Int = 0
    @Volatile internal var workerRuns: Int = 0
    @Volatile internal var lastWorkerOutcome: AutoSyncOutcome? = null
    @Volatile private var knownDevice: Boolean = false
    private val deviceReads = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pulse-device-read").apply { isDaemon = true }
    }
    private val chainWrites = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pulse-sync-chain").apply { isDaemon = true }
    }

    /** How long [runWorker] waits for a link that is still coming up, then for the sync. */
    @Volatile
    internal var workerWaitMs: Long = AutoSync.WORKER_WAIT_MS

    fun start() {
        if (started) return
        started = true
        armFinishWatch()
        armLinkWatch()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    on(AutoSyncTrigger.FOREGROUND)
                }
            },
        )
        ensureChain(SyncChainReason.APP_START)
    }

    /**
     * The old five-minute in-process tick.
     *
     * It does not start a pull and it does not enqueue work. The chain is the
     * only scheduler on that interval. Tests call this where the timer used
     * to fire.
     */
    internal fun onInterval() = Unit

    /**
     * Records when a history attempt stops, and restarts the chain delay from
     * that moment when this process's worker is not the one running it.
     *
     * App start enqueues the chain at the same time the launch pull starts.
     * The first in-process finish replaces that pending request so the next
     * five minutes begin then. A later finish, including a foreground pull,
     * leaves the pending request in place. Replacing it cancelled the worker
     * before the run could succeed. [beginWorker] also skips the replace
     * while [AutoSyncWorker] is inside its run, because REPLACE would cancel
     * that worker. The worker appends its own follow-up instead.
     */
    internal fun armFinishWatch() {
        if (finishWatchInstalled) return
        finishWatchInstalled = true
        sync.watch {
            val running = sync.running.value
            if (sawRunning && !running) {
                lastFinishMs = clock()
                if (workerDepth == 0 && !retimed) {
                    retimed = true
                    chainWrites.execute {
                        if (workerDepth != 0) return@execute
                        try {
                            SyncWork.enqueue(context, syncChainRequest(SyncChainReason.AFTER_FINISH))
                        } catch (error: Exception) {
                            Log.w(BleClients.LOG_TAG, "auto-sync finish reschedule failed", error)
                        }
                    }
                }
            }
            sawRunning = running
        }
    }

    /** The chain test marks a launch pull that already finished. */
    internal fun rememberFinish(atMs: Long) {
        lastFinishMs = atMs
    }

    internal fun beginWorker() {
        workerDepth += 1
    }

    internal fun endWorker() {
        workerDepth -= 1
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
     * attempt it started. A sync that is already running is skipped. The
     * result is not [AutoSyncOutcome.STARTED]. Callers that only need to kick
     * a sync, such as a connect, still use [on].
     */
    fun runWorker(): AutoSyncOutcome {
        workerRuns += 1
        val outcome = executeWorker()
        lastWorkerOutcome = outcome
        return outcome
    }

    private fun executeWorker(): AutoSyncOutcome {
        val deadline = clock() + workerWaitMs
        while (true) {
            when (val outcome = on(AutoSyncTrigger.WORKER)) {
                AutoSyncOutcome.STARTED -> {
                    val left = (deadline - clock()).coerceAtLeast(1L)
                    return settleWorkerSync(
                        running = { sync.running.value },
                        outcome = { sync.activity.value.outcome },
                        watch = { listener -> sync.watch(listener) },
                        timeoutMs = left,
                    )
                }
                AutoSyncOutcome.IN_PROGRESS -> return outcome
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
                readyForHistory(link.snapshot()) && coolingDown() -> AutoSyncOutcome.COOLING_DOWN
                else -> AutoSyncOutcome.IDLE
            }
        }
    }

    private var linkWatchInstalled = false

    private fun coolingDown(): Boolean {
        val now = clock()
        val sinceFinish = lastFinishMs?.let { now - it }
        if (sinceFinish != null && sinceFinish < AutoSync.CHAIN_COOLDOWN_MS) return true
        val sinceStart = lastStartMs?.let { now - it }
        return sinceStart != null && sinceStart < AutoSync.FOREGROUND_COOLDOWN_MS
    }

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
        val now = clock()
        val elapsed = lastStartMs?.let { now - it }
        val sinceFinish = lastFinishMs?.let { now - it }
        return AutoSyncInput(
            trigger = trigger,
            linked = readyForHistory(snapshot),
            running = sync.running.value,
            millisSinceLastStart = elapsed,
            millisSinceLastFinish = sinceFinish,
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

    /** App start and boot. KEEP so a live chain is left alone. */
    fun ensureChain(reason: SyncChainReason = SyncChainReason.APP_START) {
        SyncWork.enqueue(context, syncChainRequest(reason))
    }

    /**
     * After a worker returns success. One follow-up, five minutes from this
     * call, which is the end of [AutoSyncWorker.doWork].
     */
    fun scheduleAfterRun() {
        retimed = true
        SyncWork.enqueue(context, syncChainRequest(SyncChainReason.AFTER_RUN))
    }
}
