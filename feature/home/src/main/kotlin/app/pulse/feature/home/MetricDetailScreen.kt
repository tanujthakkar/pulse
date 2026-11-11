package app.pulse.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.ViewModel
import app.pulse.data.DayMetricsSource
import app.pulse.data.StoredBodyProfile
import app.pulse.metrics.ChangeTone
import app.pulse.metrics.ChartRange
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val METRIC_ROUTE: String = "metric/{tag}/{end}"

fun metricRoute(tag: String, end: LocalDate): String = "metric/$tag/$end"

@HiltViewModel
class MetricDetailViewModel @Inject constructor(
    private val days: DayMetricsSource,
    private val profiles: StoredBodyProfile,
    saved: SavedStateHandle,
) : ViewModel() {
    private val tag: String = saved.get<String>("tag") ?: "dash_hrv"
    private val end: LocalDate = saved.get<String>("end")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: LocalDate.now()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pulse-metric").apply { isDaemon = true }
    }
    private val _state = MutableStateFlow<MetricDetailState?>(null)
    val state: StateFlow<MetricDetailState?> = _state.asStateFlow()
    private var range: ChartRange = ChartRange.WEEK

    init {
        reload()
    }

    fun select(next: ChartRange) {
        range = next
        reload()
    }

    private fun reload() {
        val selected = range
        executor.execute {
            val snapshot = days.cachedSnapshot() ?: days.snapshot()
            val zone = runCatching { ZoneId.of(snapshot.book.zone) }.getOrElse { ZoneId.systemDefault() }
            val spec = metricSpec(tag, zone) ?: return@execute
            val history = snapshot.book.days
            val profile = profiles.current()
            val overrides = if (tag == "sleep_restorative") restorativeOverrides(history, end) else emptyMap()
            val series = seriesFor(spec, history, end, selected, profile, overrides)
            _state.value = presentSeries(spec, series, selected)
        }
    }

    private fun restorativeOverrides(
        history: List<app.pulse.metrics.DayResult>,
        end: LocalDate,
    ): Map<LocalDate, Double?> {
        return history
            .filter { it.main != null && !it.date.isAfter(end) }
            .takeLast(14)
            .associate { day ->
                val main = day.main ?: return@associate day.date to null
                val samples = days.windowRecords(main.startUnix, main.endUnix + 1)
                day.date to restorativeSeconds(samples, main.startUnix, main.endUnix, day.rhythm?.restingHr)
            }
    }

    override fun onCleared() {
        executor.shutdown()
    }
}

@Composable
fun MetricDetailRoute(onBack: () -> Unit, viewModel: MetricDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    MetricDetailScreen(state, onBack, viewModel::select)
}

@Composable
fun MetricDetailScreen(
    state: MetricDetailState?,
    onBack: () -> Unit,
    onRange: (ChartRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(PulsePalette.background)
            .padding(PulseSpace.md)
            .testTag("metric_detail"),
        verticalArrangement = Arrangement.spacedBy(PulseSpace.md),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("metric_back")) {
            Text("Back", color = PulsePalette.text)
        }
        if (state == null) {
            Text("Reading this metric.", color = PulsePalette.muted, modifier = Modifier.testTag("metric_reading"))
            return@Column
        }
        Text(state.title, color = PulsePalette.text, fontSize = PulseType.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("metric_title"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RangeChip("Week", state.range == ChartRange.WEEK, "metric_week") { onRange(ChartRange.WEEK) }
            RangeChip("Month", state.range == ChartRange.MONTH, "metric_month") { onRange(ChartRange.MONTH) }
            RangeChip("6-Month", state.range == ChartRange.SIX_MONTH, "metric_half") { onRange(ChartRange.SIX_MONTH) }
        }
        Text(state.averageText, color = PulsePalette.text, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("metric_average"))
        val note = state.note
        if (note != null && state.unit == null) {
            Text(note, color = PulsePalette.muted, fontSize = PulseType.label)
        }
        TrendChart(
            points = state.points.map {
                ChartMark(it.label, it.value?.toFloat(), highlighted = it.highlighted)
            },
            style = if (state.line) ChartStyle.LINE else ChartStyle.BARS,
            yMin = state.yMin.toFloat(),
            yMax = state.yMax.toFloat(),
            bandLow = state.bandLow?.toFloat(),
            bandHigh = state.bandHigh?.toFloat(),
            tag = "metric_chart",
        )
        val change = state.changeText
        Text(
            change ?: "No earlier range to compare.",
            color = when (state.tone) {
                ChangeTone.IMPROVED -> PulsePalette.green
                ChangeTone.WORSENED -> PulsePalette.yellow
                ChangeTone.NEUTRAL -> PulsePalette.muted
            },
            fontSize = PulseType.body,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("metric_change"),
        )
        Text(
            "The band is the average of these points, plus or minus one standard deviation.",
            color = PulsePalette.muted,
            fontSize = PulseType.label,
        )
    }
}

@Composable
private fun RangeChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .background(if (selected) PulsePalette.track else Color.Transparent, RoundedCornerShape(50))
            .testTag(tag),
    ) {
        Text(label, color = if (selected) PulsePalette.text else PulsePalette.muted)
    }
}
