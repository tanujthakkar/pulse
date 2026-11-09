package app.pulse.link

import android.os.Handler
import android.os.Looper
import android.util.Log
import app.pulse.ble.BleClients
import app.pulse.sync.historyAckReport
import app.pulse.sync.HistoryAckEnabled
import app.pulse.sync.HistoryLink
import app.pulse.sync.HistoryScheduler
import app.pulse.sync.HistorySubscription
import app.pulse.sync.HistorySyncDriver
import app.pulse.sync.MemoryHistoryAck
import app.pulse.sync.PhoneClock
import app.pulse.sync.SyncActivity
import app.pulse.sync.SyncStore
import app.pulse.sync.phoneTimeFromUnixMillis
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide owner of [HistorySyncDriver].
 *
 * [StrapLinkService] retains this object for as long as the service is up.
 * The driver, and the [app.pulse.sync.HistorySyncEngine] it creates for an
 * attempt, are not held by an Activity or a ViewModel. Pausing or stopping
 * the Activity does not cancel an attempt. The engine ends when the attempt
 * finishes or the command port reports that the strap link dropped.
 *
 * [activity] is the live session view the Sync screen renders: phase, chunk
 * and record counts, acknowledgement counters, and the last outcome with its
 * time. [app.pulse.wire.codec.HistoryAckGate] stays closed. Each saved chunk
 * is acknowledged when [ack] is on and the hello firmware is 50.43.1.0.
 * The switch is not restored from a chunk row.
 */
@Singleton
class HistorySyncHost @Inject constructor(
    commands: StrapCommandPort,
    store: SyncStore,
    link: StrapLink,
    private val ack: HistoryAckEnabled = MemoryHistoryAck(),
) {
    private val driver = HistorySyncDriver(
        link = CommandHistoryLink(commands),
        store = store,
        phone = PhoneClock { phoneTimeFromUnixMillis(System.currentTimeMillis()) },
        schedule = MainHistoryScheduler(),
        historyAckEnabled = { ack.enabled() },
        setHistoryAckEnabled = { enabled -> ack.setEnabled(enabled) },
        firmware = { link.snapshot().helloFirmware },
        log = { line -> Log.i(BleClients.LOG_TAG, line) },
    )

    private val statusOut = MutableStateFlow(driver.status)
    val status: StateFlow<String> = statusOut.asStateFlow()

    private val runningOut = MutableStateFlow(driver.running)
    val running: StateFlow<Boolean> = runningOut.asStateFlow()

    private val activityOut = MutableStateFlow(driver.activity)
    val activity: StateFlow<SyncActivity> = activityOut.asStateFlow()

    private val listeners = mutableListOf<() -> Unit>()

    init {
        driver.onChange = {
            statusOut.value = driver.status
            runningOut.value = driver.running
            activityOut.value = driver.activity
            listeners.toList().forEach { it() }
        }
        driver.refreshSaved()
        driver.publishIdle()
    }

    fun syncNow() {
        driver.syncNow()
    }

    /** Stops or resumes acknowledgement of chunks saved after this call. */
    fun setHistoryAckEnabled(enabled: Boolean) {
        driver.setAckEnabled(enabled)
    }

    /**
     * Text for a debug export.
     *
     * Strap result counts are separate from GATT status 0. This text does not
     * include tokens or stored rows.
     */
    fun acknowledgementNote(): String = historyAckReport(driver.activity)

    /** Notifies [listener] now and after each driver change. The returned function removes it. */
    fun watch(listener: () -> Unit): () -> Unit {
        listeners += listener
        listener()
        return { listeners.remove(listener) }
    }
}

/**
 * [StrapCommandPort] as a [HistoryLink].
 *
 * Notifications and link-down events are delivered on the main looper so the
 * engine's start, notify, disconnect, and watchdog calls stay serialized.
 */
private class CommandHistoryLink(
    private val commands: StrapCommandPort,
) : HistoryLink {
    private val main = Handler(Looper.getMainLooper())

    override val address: String?
        get() = commands.address

    override fun write(frame: ByteArray): Boolean = commands.write(frame)

    override fun listen(
        onNotification: (source: String, bytes: ByteArray) -> Unit,
        onDown: () -> Unit,
    ): HistorySubscription {
        val remove = commands.listen(
            onNotification = { source, bytes -> main.post { onNotification(source, bytes) } },
            onDown = { main.post { onDown() } },
        )
        return HistorySubscription { remove() }
    }
}

private class MainHistoryScheduler : HistoryScheduler {
    private val main = Handler(Looper.getMainLooper())

    override fun after(delayMs: Long, task: () -> Unit): HistorySubscription {
        val runnable = Runnable { task() }
        main.postDelayed(runnable, delayMs)
        return HistorySubscription { main.removeCallbacks(runnable) }
    }
}
