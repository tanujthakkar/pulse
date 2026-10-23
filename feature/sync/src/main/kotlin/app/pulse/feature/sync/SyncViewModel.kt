package app.pulse.feature.sync

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.ViewModel
import app.pulse.data.DataBuildInfo
import app.pulse.link.LinkBuildInfo
import app.pulse.link.StrapCommandPort
import app.pulse.sync.HistoryLink
import app.pulse.sync.HistoryScheduler
import app.pulse.sync.HistorySubscription
import app.pulse.sync.HistorySyncDriver
import app.pulse.sync.PhoneClock
import app.pulse.sync.SyncActivity
import app.pulse.sync.SyncStore
import app.pulse.sync.phoneTimeFromUnixMillis
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manual history sync.
 *
 * The button starts one attempt. [activity] is the session state, the live
 * chunk and record counts, and the last outcome with its time. The button
 * stays disabled while that attempt runs. Acknowledgements stay behind the
 * metadata gate.
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    store: SyncStore,
    commands: StrapCommandPort,
) : ViewModel() {
    val title: String = "Sync"

    /** Keeps the link and data modules on the sync classpath. */
    val modulePins: String = "${LinkBuildInfo.SPEC}:${DataBuildInfo.SPEC}"

    private val driver = HistorySyncDriver(
        link = StrapHistoryLink(commands),
        store = store,
        phone = PhoneClock { phoneTimeFromUnixMillis(System.currentTimeMillis()) },
        schedule = MainHistoryScheduler(),
        log = { line -> Log.i(LOG_TAG, line) },
    )

    private val _activity = MutableStateFlow(driver.activity)
    val activity: StateFlow<SyncActivity> = _activity.asStateFlow()

    init {
        driver.onChange = { _activity.value = driver.activity }
    }

    fun syncNow() {
        driver.syncNow()
    }

    private companion object {
        const val LOG_TAG: String = "PulseGatt"
    }
}

private class StrapHistoryLink(
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
