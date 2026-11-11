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
import app.pulse.metrics.activityWindow
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
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
 * This does not send strap commands. Live bpm is whatever the open session
 * has already published on the standard heart-rate measurement. The trace is
 * the last few minutes of those samples, kept in memory only.
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
    private var trace: List<HeartPoint> = emptyList()
    @Volatile private var strains: Map<Long, String> = emptyMap()
    private val onLink: (LinkSnapshot) -> Unit = { next ->
        linkState = next
        publish()
    }

    init {
        bpm = heart.latest.value?.bpm
        trace = heart.recent().map { HeartPoint(it.tsMillis, it.bpm) }
        link.watch(onLink)
        viewModelScope.launch {
            heart.latest.collect { sample ->
                bpm = sample?.bpm
                if (sample == null) trace = emptyList()
                publish()
            }
        }
        viewModelScope.launch {
            heart.samples.collect { sample ->
                bpm = sample.bpm
                trace = heart.recent().map { HeartPoint(it.tsMillis, it.bpm) }
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
            strains = strainLabels(rows)
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
            strains = strainLabels(logged)
            publish()
        }
    }

    fun deleteActivity(id: Long) {
        executor.execute {
            activities.delete(id)
            logged = activities.all()
            strains = strainLabels(logged)
            publish()
        }
    }

    private fun publish() {
        val date = selected ?: return
        val zone = zoneOf(snapshot.book.zone)
        val day = snapshot.book.days.firstOrNull { it.date == date }
        val history = snapshot.book.days.filter {
            it.date.isBefore(date) && !it.date.isBefore(date.minusDays(30))
        }
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
                trace = trace,
            ),
            history = history,
            strains = strains,
        )
    }

    private fun strainLabels(rows: List<LoggedActivity>): Map<Long, String> {
        val zone = zoneOf(snapshot.book.zone)
        return rows.associate { item ->
            val label = try {
                val day = Instant.ofEpochSecond(item.startUnix).atZone(zone).toLocalDate()
                val resting = snapshot.book.days.firstOrNull { it.date == day }?.rhythm?.restingHr
                val stats = activityWindow(
                    days.windowRecords(item.startUnix, item.endUnix),
                    item.startUnix,
                    item.endUnix,
                    resting,
                )
                stats.approximateStrain?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
            } catch (_: Exception) {
                "--"
            }
            item.startUnix to label
        }
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
