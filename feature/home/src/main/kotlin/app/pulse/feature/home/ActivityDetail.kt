package app.pulse.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import app.pulse.data.ActivityLog
import app.pulse.data.DayMetricsSource
import app.pulse.metrics.ActivityKind
import app.pulse.metrics.ActivityWindowStats
import app.pulse.metrics.DayRules
import app.pulse.metrics.HeartChartPoint
import app.pulse.metrics.Rhythm
import app.pulse.metrics.activityWindow
import app.pulse.metrics.sleepRhythm
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActivityDetail(
    val title: String,
    val rows: List<Pair<String, String>>,
    val chart: List<HeartChartPoint>,
    val note: String,
)

@HiltViewModel
class ActivityDetailViewModel @Inject constructor(
    days: DayMetricsSource,
    activities: ActivityLog,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val _detail = MutableStateFlow<ActivityDetail?>(null)
    val detail: StateFlow<ActivityDetail?> = _detail.asStateFlow()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pulse-activity").apply { isDaemon = true }
    }

    init {
        val kind = savedState.get<String>("kind") ?: ActivityKind.MANUAL.name
        val start = savedState.get<String>("start")?.toLongOrNull() ?: 0L
        val end = savedState.get<String>("end")?.toLongOrNull() ?: 0L
        executor.execute {
            _detail.value = loadActivityDetail(kind, start, end, days, activities)
        }
    }

    override fun onCleared() {
        executor.shutdown()
    }
}

internal fun loadActivityDetail(
    kindName: String,
    start: Long,
    end: Long,
    days: DayMetricsSource,
    activities: ActivityLog,
): ActivityDetail {
    val kind = runCatching { ActivityKind.valueOf(kindName) }.getOrDefault(ActivityKind.MANUAL)
    val cached = days.cachedSnapshot()
    val zone = cached?.book?.zone?.let { zoneOf(it) } ?: ZoneId.systemDefault()
    if (kind == ActivityKind.SLEEP) {
        // The card's end is the last sleep sample, inclusive. The window query is exclusive.
        val records = days.windowRecords(start, end + 1)
        val asleep = records.count { it.unixSeconds in start..end && (it.bandMotion == DayRules.SLEEP || it.edgeWindowSleep) }
        val edge = records.isNotEmpty() && records.all { it.edgeWindowSleep && it.bandMotion != DayRules.SLEEP }
        val rhythm = sleepRhythm(records, start, end, edge)
        return sleepDetail(
            start,
            end,
            zone,
            rhythm,
            edge,
            asleepSeconds(if (asleep > 0) asleep else null, start, end),
        )
    }
    val records = days.windowRecords(start, end)
    val date = Instant.ofEpochSecond(start).atZone(zone).toLocalDate()
    val resting = cached?.book?.days?.firstOrNull { it.date == date }?.rhythm?.restingHr
    val stats = activityWindow(records, start, end, resting)
    val title = activities.all().firstOrNull { it.startUnix == start && it.endUnix == end }?.type ?: "Activity"
    return workoutDetail(title, stats)
}

fun workoutDetail(title: String, stats: ActivityWindowStats): ActivityDetail {
    val strain = stats.approximateStrain?.let { String.format(Locale.US, "%.1f approximate", it) } ?: "—"
    return ActivityDetail(
        title = title,
        rows = listOf(
            "Duration" to formatDuration(stats.durationSeconds),
            "Average HR" to bpm(stats.averageHr),
            "Max HR" to bpm(stats.maxHr),
            "Below 100" to seconds(stats.zones.below100),
            "100–119" to seconds(stats.zones.from100),
            "120–139" to seconds(stats.zones.from120),
            "140–159" to seconds(stats.zones.from140),
            "160+" to seconds(stats.zones.from160),
            "Strain" to strain,
        ),
        chart = stats.chart,
        note = "Strain contribution is approximate. It is not a strain score.",
    )
}

fun sleepDetail(
    start: Long,
    end: Long,
    zone: ZoneId,
    rhythm: Rhythm?,
    edgeProvided: Boolean,
    asleepSeconds: Long = (end - start).coerceAtLeast(0L),
): ActivityDetail {
    val onset = clock(start, zone) + if (edgeProvided) " · Edge-provided" else ""
    val hrv = rhythm?.rmssdMs?.let { "${it.roundToInt()} ms" } ?: "—"
    val resting = rhythm?.restingHr?.let { "$it bpm" } ?: "—"
    return ActivityDetail(
        title = "Sleep",
        rows = listOf(
            "Duration" to formatDuration(asleepSeconds),
            "Onset" to onset,
            "Offset" to clock(end, zone),
            "HRV" to hrv,
            "Resting HR" to resting,
        ),
        chart = emptyList(),
        note = "Stages are not in the stored history.",
    )
}

@Composable
fun ActivityDetailRoute(onBack: () -> Unit, viewModel: ActivityDetailViewModel = hiltViewModel()) {
    val detail by viewModel.detail.collectAsState()
    ActivityDetailScreen(detail, onBack)
}

@Composable
fun ActivityDetailScreen(detail: ActivityDetail?, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(PulsePalette.background)
            .verticalScroll(rememberScrollState())
            .padding(PulseSpace.md)
            .testTag("activity_detail"),
        verticalArrangement = Arrangement.spacedBy(PulseSpace.sm),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("activity_back")) {
            Text("Back", color = PulsePalette.text)
        }
        if (detail == null) {
            Text("Reading this activity.", color = PulsePalette.muted, modifier = Modifier.testTag("activity_reading"))
            return@Column
        }
        Text(detail.title, color = PulsePalette.text, fontSize = PulseType.title, modifier = Modifier.testTag("activity_title"))
        detail.rows.forEach { (label, value) ->
            MetricCard(label, value, "detail_${label.lowercase().replace(' ', '_')}")
        }
        if (detail.chart.isNotEmpty()) {
            HeartChart(detail.chart)
        }
        Text(detail.note, color = PulsePalette.muted, fontSize = PulseType.body, modifier = Modifier.testTag("activity_note"))
    }
}

@Composable
private fun HeartChart(points: List<HeartChartPoint>) {
    if (points.size < 2) return
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .padding(vertical = PulseSpace.sm)
            .testTag("activity_chart"),
    ) {
        val min = points.minOf { it.bpm }.toFloat()
        val max = points.maxOf { it.bpm }.toFloat().coerceAtLeast(min + 1f)
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = index.toFloat() / (points.size - 1).toFloat() * size.width
            val y = size.height - (point.bpm - min) / (max - min) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, PulsePalette.blue, style = Stroke(width = 3.dp.toPx()))
    }
}

private fun bpm(value: Int?): String = value?.let { "$it bpm" } ?: "—"

private fun seconds(count: Int): String = formatDuration(count.toLong())

private fun clock(unixSeconds: Long, zone: ZoneId): String =
    CLOCK.format(Instant.ofEpochSecond(unixSeconds).atZone(zone))

private fun zoneOf(id: String): ZoneId = try {
    ZoneId.of(id)
} catch (_: Exception) {
    ZoneId.systemDefault()
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
