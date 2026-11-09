package app.pulse.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.data.DayMetricsSource
import app.pulse.data.DaySnapshot
import app.pulse.link.LinkSnapshot
import app.pulse.link.LiveHeartRateFeed
import app.pulse.link.StrapLink
import app.pulse.metrics.DayBook
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
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
 * Home reads the day book that Days already computes.
 *
 * It does not send strap commands. Live bpm, battery, and connection are
 * whatever the open session has already published.
 */
@HiltViewModel
class HomeViewModel internal constructor(
    private val days: DayMetricsSource,
    private val link: StrapLink,
    private val heart: LiveHeartRateFeed,
    private val clock: () -> LocalDate,
    private val executor: Executor,
) : ViewModel() {
    @Inject
    constructor(
        days: DayMetricsSource,
        link: StrapLink,
        heart: LiveHeartRateFeed,
    ) : this(days, link, heart, { LocalDate.now() }, newHomeExecutor())

    private val generation = AtomicInteger()
    private val _ui = MutableStateFlow<HomeContent?>(null)
    val ui: StateFlow<HomeContent?> = _ui.asStateFlow()

    private var snapshot = DaySnapshot(DayBook(ZoneId.systemDefault().id, emptyList(), null), null, null)
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
            if (generation.get() != ticket) return@execute
            snapshot = loaded
            if (selected == null) selected = initialDate(loaded.book, clock())
            publish()
        }
    }

    fun previous() = move(-1)

    fun next() = move(1)

    private fun move(delta: Long) {
        val current = selected ?: return
        val bounds = bounds(snapshot.book, clock())
        val target = current.plusDays(delta)
        if (target < bounds.first || target > bounds.second) return
        selected = target
        publish()
    }

    private fun publish() {
        val date = selected ?: return
        val bounds = bounds(snapshot.book, clock())
        val zone = zoneOf(snapshot.book.zone)
        val day = snapshot.book.days.firstOrNull { it.date == date }
        _ui.value = homeContent(
            day = day,
            date = date,
            canGoPrevious = date > bounds.first,
            canGoNext = date < bounds.second,
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

internal fun initialDate(book: DayBook, today: LocalDate): LocalDate =
    book.days.maxOfOrNull { it.date } ?: today

internal fun bounds(book: DayBook, today: LocalDate): Pair<LocalDate, LocalDate> {
    val stored = book.days.map { it.date }
    val earliest = stored.minOrNull() ?: today
    val latest = maxOf(stored.maxOrNull() ?: today, today)
    return earliest to latest
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
