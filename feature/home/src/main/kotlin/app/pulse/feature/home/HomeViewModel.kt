package app.pulse.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.data.ActivityLog
import app.pulse.data.DayMetricsSource
import app.pulse.data.DaySnapshot
import app.pulse.link.LinkSnapshot
import app.pulse.link.LiveHeartRateFeed
import app.pulse.link.StrapLink
import app.pulse.metrics.DayBook
import app.pulse.metrics.LoggedActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Home reads the stored day book and the typed activities.
 *
 * Changing the visible month does not come through here. [pick] shows a date
 * from the book already loaded and does not read it again. Adding an activity
 * writes the activity row only.
 *
 * This does not send strap commands. Live bpm and battery are whatever the
 * open session has already published.
 */
@HiltViewModel
class HomeViewModel internal constructor(
    private val days: DayMetricsSource,
    private val activities: ActivityLog,
    private val link: StrapLink,
    private val heart: LiveHeartRateFeed,
    private val clock: () -> LocalDate,
    private val executor: Executor,
) : ViewModel() {
    @Inject
    constructor(
        days: DayMetricsSource,
        activities: ActivityLog,
        link: StrapLink,
        heart: LiveHeartRateFeed,
    ) : this(days, activities, link, heart, { LocalDate.now() }, newHomeExecutor())

    private val generation = AtomicInteger()
    private val _ui = MutableStateFlow<HomeContent?>(null)
    val ui: StateFlow<HomeContent?> = _ui.asStateFlow()

    private var snapshot = DaySnapshot(DayBook(ZoneId.systemDefault().id, emptyList(), null), null, null)
    private var logged: List<LoggedActivity> = emptyList()
    private var selected: LocalDate? = null
    private var linkState = LinkSnapshot()
    private var bpm: Int? = null
    private val onLink: (LinkSnapshot) -> Unit = { next ->
        linkState = next
        publish()
    }

    init {
        link.watch(onLink)
        viewModelScope.launch {
            heart.latest.collect { sample ->
                bpm = sample?.bpm
                publish()
            }
        }
        refresh()
    }

    fun refresh() {
        val ticket = generation.incrementAndGet()
        executor.execute {
            val loaded = try {
                days.snapshot()
            } catch (_: Exception) {
                DaySnapshot(DayBook(ZoneId.systemDefault().id, emptyList(), "Day metrics could not be read."), null, null)
            }
            val rows = try {
                activities.all()
            } catch (_: Exception) {
                emptyList()
            }
            if (generation.get() != ticket) return@execute
            snapshot = loaded
            logged = rows
            if (selected == null) selected = clock()
            publish()
        }
    }

    /** Shows [date] from the book already in memory. Does not read the store. */
    fun pick(date: LocalDate) {
        selected = date
        publish()
    }

    fun backToToday() {
        selected = clock()
        publish()
    }

    fun saveActivity(id: Long?, type: String, start: LocalTime, end: LocalTime) {
        val date = selected ?: return
        val trimmed = type.trim()
        if (trimmed.isEmpty() || start == end) return
        val zone = zoneOf(snapshot.book.zone)
        val startUnix = date.atTime(start).atZone(zone).toEpochSecond()
        val endDate = if (end.isAfter(start)) date else date.plusDays(1)
        val endUnix = endDate.atTime(end).atZone(zone).toEpochSecond()
        if (endUnix <= startUnix) return
        executor.execute {
            activities.save(id, trimmed, startUnix, endUnix)
            logged = activities.all()
            publish()
        }
    }

    fun deleteActivity(id: Long) {
        executor.execute {
            activities.delete(id)
            logged = activities.all()
            publish()
        }
    }

    private fun publish() {
        val date = selected ?: return
        val zone = zoneOf(snapshot.book.zone)
        val day = snapshot.book.days.firstOrNull { it.date == date }
        _ui.value = homeContent(
            day = day,
            date = date,
            today = clock(),
            logged = logged,
            live = HomeLive(
                bpm = bpm,
                phase = linkState.phase,
                batteryPercent = shownBattery(linkState, snapshot.batteryPercent),
                lastSyncUnixSeconds = snapshot.lastSyncUnixSeconds,
                zone = zone,
            ),
        )
    }

    override fun onCleared() {
        link.unwatch(onLink)
        (executor as? ExecutorService)?.shutdown()
    }
}

internal fun shownBattery(link: LinkSnapshot, stored: Int?): Int? {
    link.serviceBatteryPercent?.let { return it }
    link.commandBatteryPercent?.let { return it.toInt() }
    return stored
}

private fun zoneOf(id: String): ZoneId = try {
    ZoneId.of(id)
} catch (_: Exception) {
    ZoneId.systemDefault()
}

private fun newHomeExecutor(): ExecutorService =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pulse-home").apply { isDaemon = true }
    }
