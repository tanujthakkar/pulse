package app.pulse.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun SleepReportScreen(
    report: SleepReport,
    onBack: () -> Unit,
    onOpenMetric: (String, LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier
            .fillMaxSize()
            .background(PulsePalette.background)
            .padding(horizontal = PulseSpace.md)
            .testTag("sleep_report"),
        verticalArrangement = Arrangement.spacedBy(PulseSpace.md),
    ) {
        item { SleepHeader(report, onBack) }
        item { SleepPerformanceBlock(report) }
        item { SleepBreakdownCard(report) }
        item { LastNightBlock(report) }
        item { HoursNeededCard(report) }
        item { ConsistencyCard(report) }
        item { EfficiencyCard(report) }
        item { StressCard(report) }
        item { WeeklyTrendsBlock(report, onOpenMetric) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
internal fun SleepHeader(report: SleepReport, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("sleep_back")) {
            Text("Back", color = PulsePalette.text)
        }
        Text(
            report.dateLabel,
            color = PulsePalette.text,
            fontWeight = FontWeight.SemiBold,
            fontSize = PulseType.label,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).testTag("sleep_date"),
        )
        val battery = report.batteryPercent
        Text(
            if (battery == null) "" else "$battery%",
            color = PulsePalette.muted,
            fontSize = PulseType.label,
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
internal fun SleepPerformanceBlock(report: SleepReport) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            PerformanceRing(report.performancePercent?.div(100f) ?: 0f, report.band)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (report.performancePercent == null) "--" else "${report.performancePercent}%",
                    color = PulsePalette.text,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("sleep_performance"),
                )
                Text(
                    "SLEEP\nPERFORMANCE",
                    color = PulsePalette.muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        BandSteps(report.band)
    }
}

@Composable
private fun PerformanceRing(progress: Float, band: SleepBand?) {
    val color = band.color()
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 14.dp.toPx()
        drawArc(
            color = PulsePalette.track,
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = 135f,
            sweepAngle = 270f * progress.coerceIn(0f, 1f),
            useCenter = false,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun BandSteps(active: SleepBand?) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        SleepBand.entries.forEach { band ->
            Box(
                Modifier
                    .width(36.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (band == active) band.color() else PulsePalette.track),
            )
        }
    }
}

@Composable
internal fun SleepBreakdownCard(report: SleepReport) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(PulsePalette.surface, RoundedCornerShape(18.dp))
            .padding(PulseSpace.md)
            .testTag("sleep_breakdown"),
        verticalArrangement = Arrangement.spacedBy(PulseSpace.md),
    ) {
        report.breakdown.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(row.tag)) {
                Text(row.name, color = PulsePalette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                SegmentBar(row.percent, row.band, Modifier.width(72.dp))
                Text(
                    if (row.percent == null) "--" else "${row.percent}%",
                    color = PulsePalette.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = PulseType.body,
                    modifier = Modifier.width(48.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
        BandLegend()
    }
}

@Composable
private fun SegmentBar(percent: Int?, band: SleepBand?, modifier: Modifier) {
    val fraction = ((percent ?: 0) / 100f).coerceIn(0f, 1f)
    Box(modifier.height(8.dp).clip(RoundedCornerShape(50)).background(PulsePalette.track)) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(8.dp)
                .background(band.color()),
        )
    }
}

@Composable
private fun BandLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(PulseSpace.md)) {
        LegendDot(PulsePalette.orange, "Poor")
        LegendDot(PulsePalette.muted, "Sufficient")
        LegendDot(PulsePalette.green, "Optimal")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, color = PulsePalette.muted, fontSize = 11.sp)
    }
}

@Composable
internal fun LastNightBlock(report: SleepReport) {
    Column(verticalArrangement = Arrangement.spacedBy(PulseSpace.sm), modifier = Modifier.testTag("sleep_last_night")) {
        Text("Last Night's Sleep", color = PulsePalette.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text("${titleCase(report.dateLabel)} vs. prior 30 days", color = PulsePalette.muted, fontSize = PulseType.label)
        Column(
            Modifier
                .fillMaxWidth()
                .background(PulsePalette.surface, RoundedCornerShape(18.dp))
                .padding(PulseSpace.md),
            verticalArrangement = Arrangement.spacedBy(PulseSpace.sm),
        ) {
            Text("HOURS OF SLEEP", color = PulsePalette.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
            val average = report.asleepAverageSeconds
            val worse = average != null && report.asleepSeconds < average
            val better = average != null && report.asleepSeconds > average
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatClockDuration(report.asleepSeconds),
                    color = PulsePalette.text,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("sleep_hours"),
                )
                if (average != null && report.asleepSeconds != average) {
                    Text(
                        if (worse) " ↓" else " ↑",
                        color = if (better) PulsePalette.green else PulsePalette.yellow,
                        fontSize = 22.sp,
                    )
                }
            }
            if (average != null) {
                Text(formatClockDuration(average), color = PulsePalette.muted, fontSize = PulseType.body)
            }
            HeartNightChart(report)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(report.onsetLabel, color = PulsePalette.muted, fontSize = 11.sp)
                Text(report.wakeLabel, color = PulsePalette.muted, fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("TYPICAL RANGE", color = PulsePalette.muted, fontSize = 11.sp, letterSpacing = 0.6.sp)
                Text("DURATION ${formatClockDuration(report.inBedSeconds)}", color = PulsePalette.muted, fontSize = 11.sp)
            }
            val stages = report.stages
            if (stages == null) {
                Text(report.stageNote, color = PulsePalette.muted, fontSize = PulseType.body, modifier = Modifier.testTag("sleep_stages_unavailable"))
            } else {
                Text(report.stageNote, color = PulsePalette.muted, fontSize = 11.sp, modifier = Modifier.testTag("sleep_stages_estimated"))
                StageRow("AWAKE", stages.awake, PulsePalette.muted)
                StageRow("LIGHT", stages.light, PulsePalette.lightStage)
                StageRow("SWS (DEEP)", stages.deep, PulsePalette.deep)
                StageRow("REM", stages.rem, PulsePalette.rem)
                val restorative = report.restorativeSeconds
                if (restorative != null) {
                    Text(
                        "Restorative ${formatClockDuration(restorative)}",
                        color = PulsePalette.text,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("sleep_restorative"),
                    )
                }
            }
        }
    }
}

@Composable
private fun StageRow(name: String, slice: StageSlice, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$name  ${slice.percent}%", color = color, fontSize = PulseType.label, fontWeight = FontWeight.SemiBold)
            Text(formatClockDuration(slice.seconds), color = PulsePalette.text, fontSize = PulseType.label)
        }
        Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(4.dp)).background(PulsePalette.track)) {
            Hatch(Modifier.fillMaxSize())
            Box(Modifier.fillMaxWidth(slice.percent / 100f).height(12.dp).background(color))
            val typical = slice.typicalPercent
            if (typical != null) {
                Box(
                    Modifier
                        .fillMaxWidth(typical.coerceIn(0, 100) / 100f)
                        .height(12.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(Modifier.width(2.dp).height(12.dp).background(PulsePalette.text.copy(alpha = 0.7f)))
                }
            }
        }
    }
}

@Composable
private fun Hatch(modifier: Modifier) {
    Canvas(modifier) {
        val gap = 8.dp.toPx()
        var x = -size.height
        while (x < size.width) {
            drawLine(PulsePalette.background.copy(alpha = 0.45f), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 2f)
            x += gap
        }
    }
}

@Composable
private fun HeartNightChart(report: SleepReport) {
    val points = report.heart
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .testTag("sleep_hr"),
    ) {
        if (points.size < 2) return@Canvas
        val min = (points.min() - 5).coerceAtMost(40).toFloat()
        val max = (points.max() + 5).coerceAtLeast(100).toFloat()
        val path = androidx.compose.ui.graphics.Path()
        points.forEachIndexed { index, bpm ->
            val x = index.toFloat() / (points.size - 1) * size.width
            val y = size.height * (1f - (bpm - min) / (max - min))
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, PulsePalette.blue, style = Stroke(width = 2.dp.toPx()))
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        drawLine(PulsePalette.muted, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 2f, pathEffect = dash)
        drawLine(PulsePalette.muted, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = 2f, pathEffect = dash)
    }
}

@Composable
internal fun HoursNeededCard(report: SleepReport) {
    MetricSurface("HOURS VS. NEEDED", "sleep_need_card") {
        ComparePercent(report.needPercent, report.priorNeedPercent)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("HOURS OF SLEEP", color = PulsePalette.muted, fontSize = 11.sp)
            Text(formatClockDuration(report.asleepSeconds), color = PulsePalette.text, fontWeight = FontWeight.SemiBold)
        }
        val fraction = (report.asleepSeconds.toFloat() / report.needSeconds.coerceAtLeast(1)).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(6.dp)).background(PulsePalette.track)) {
            Box(Modifier.fillMaxWidth(fraction).height(14.dp).background(PulsePalette.blue))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("SLEEP NEEDED", color = PulsePalette.muted, fontSize = 11.sp)
            Text(formatClockDuration(report.needSeconds.toLong()), color = PulsePalette.text, fontWeight = FontWeight.SemiBold)
        }
        NeedLine(PulsePalette.muted, "Healthy minimum", formatClockDuration(report.baselineSeconds.toLong()))
        NeedLine(PulsePalette.blue, "Recent strain", signedClock(report.strainSeconds))
        NeedLine(PulsePalette.track, "Sleep debt", signedClock(report.debtSeconds))
    }
}

@Composable
private fun NeedLine(color: Color, label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
            Text(label, color = PulsePalette.muted, fontSize = PulseType.label)
        }
        Text(value, color = PulsePalette.text, fontSize = PulseType.label)
    }
}

@Composable
internal fun ConsistencyCard(report: SleepReport) {
    val view = report.consistency
    MetricSurface("SLEEP CONSISTENCY", "sleep_consistency_card") {
        ComparePercent(view.percent, view.priorPercent)
        if (view.days.isEmpty()) {
            Text("Not enough nights for a bed and wake range.", color = PulsePalette.muted, fontSize = PulseType.body)
        } else {
            Text("Dashed lines are the median bed and wake.", color = PulsePalette.muted, fontSize = 11.sp)
            val marks = view.days.map {
                ChartMark(
                    label = it.label,
                    primary = it.wakeMinute.toFloat(),
                    secondary = it.bedMinute.toFloat(),
                    highlighted = it.selected,
                )
            }
            TrendChart(
                points = marks,
                style = ChartStyle.FLOATING,
                yMin = 8f * 60f,
                yMax = 22f * 60f,
                bandLow = view.optimalBed?.toFloat(),
                bandHigh = view.optimalWake?.toFloat(),
                yLabel = { minute ->
                    val clock = ((12 * 60 + minute.roundToInt()) % (24 * 60) + 24 * 60) % (24 * 60)
                    val hour = clock / 60
                    val shown = if (hour % 12 == 0) 12 else hour % 12
                    val suffix = if (hour < 12) "a" else "p"
                    "$shown$suffix"
                },
                tag = "sleep_consistency_chart",
            )
        }
    }
}

@Composable
internal fun EfficiencyCard(report: SleepReport) {
    MetricSurface("SLEEP EFFICIENCY", "sleep_efficiency_card") {
        ComparePercent(report.efficiencyPercent, report.priorEfficiency)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("ASLEEP", color = PulsePalette.muted, fontSize = 11.sp)
            Text(formatClockDuration(report.asleepSeconds), color = PulsePalette.text, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(4.dp))) {
            val timeline = report.asleepTimeline
            if (timeline.isEmpty()) {
                Box(Modifier.fillMaxSize().background(PulsePalette.track))
            } else {
                timeline.forEach { asleep ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(if (asleep) PulsePalette.blue else PulsePalette.track),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("AWAKE", color = PulsePalette.muted, fontSize = 11.sp)
            Text(formatClockDuration(report.awakeSeconds), color = PulsePalette.text)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("WAKE EVENTS", color = PulsePalette.muted, fontSize = 11.sp, letterSpacing = 0.5.sp)
            Text(report.wakeEvents.toString(), color = PulsePalette.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("sleep_wake_events"))
        }
    }
}

@Composable
internal fun StressCard(report: SleepReport) {
    MetricSurface("SLEEP STRESS", "sleep_stress_card") {
        Text("Estimated from heart rate above resting.", color = PulsePalette.muted, fontSize = 11.sp)
        ComparePercent(report.highStressPercent, null, lowerIsBetter = true)
        val marks = report.stress.mapIndexed { index, value ->
            ChartMark(if (index == 0 || index == report.stress.lastIndex) "" else "", value)
        }
        if (marks.size >= 2) {
            TrendChart(
                points = marks,
                style = ChartStyle.LINE,
                yMin = 0f,
                yMax = 3f,
                yLabel = { it.roundToInt().toString() },
                tag = "sleep_stress_chart",
            )
        }
        StressBar("HIGH", report.stressHighSeconds, report, PulsePalette.orange)
        StressBar("MEDIUM", report.stressMediumSeconds, report, PulsePalette.green)
        StressBar("LOW", report.stressLowSeconds, report, PulsePalette.blue)
    }
}

@Composable
private fun StressBar(name: String, seconds: Long, report: SleepReport, color: Color) {
    val total = (report.stressHighSeconds + report.stressMediumSeconds + report.stressLowSeconds).coerceAtLeast(1L)
    val percent = (seconds * 100 / total).toInt()
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$name  $percent%", color = color, fontSize = PulseType.label, fontWeight = FontWeight.SemiBold)
            Text(formatClockDuration(seconds), color = PulsePalette.text, fontSize = PulseType.label)
        }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(4.dp)).background(PulsePalette.track)) {
            Box(Modifier.fillMaxWidth(percent / 100f).height(10.dp).background(color))
        }
    }
}

@Composable
internal fun WeeklyTrendsBlock(report: SleepReport, onOpenMetric: (String, LocalDate) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PulseSpace.sm), modifier = Modifier.testTag("sleep_weekly")) {
        Text("Weekly Trends", color = PulsePalette.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        WeekChart(report, "SLEEP PERFORMANCE", "sleep_performance", ChartStyle.BARS, { it.performance }, onOpenMetric)
        WeekChart(
            report,
            "HOURS VS. NEEDED (HOURS)",
            "sleep_hours",
            ChartStyle.DUAL_LINE,
            { it.asleepHours },
            onOpenMetric,
            { it.neededHours },
        )
        WeekChart(report, "HOURS VS. NEEDED (%)", "sleep_hours_vs_needed", ChartStyle.BARS, { it.vsNeededPercent }, onOpenMetric)
        WeekChart(
            report,
            "RESTORATIVE SLEEP (HOURS)",
            "sleep_restorative",
            ChartStyle.STACKED,
            { it.deepHours },
            onOpenMetric,
            { it.remHours },
        )
        WeekChart(report, "SLEEP CONSISTENCY", "sleep_consistency", ChartStyle.BARS, { it.consistency }, onOpenMetric)
        WeekChart(
            report,
            "TIME IN BED",
            "sleep_time_in_bed",
            ChartStyle.FLOATING,
            { it.wakeMinute },
            onOpenMetric,
            { it.bedMinute },
            floorAtZero = false,
        )
        WeekChart(report, "SLEEP EFFICIENCY", "sleep_efficiency", ChartStyle.LINE, { it.efficiency }, onOpenMetric)
        WeekChart(report, "SLEEP DEBT (HOURS)", "sleep_debt", ChartStyle.BARS, { it.debtHours }, onOpenMetric)
        Text(
            "Personal sleep debt is the estimated need minus time asleep. The dashboard card still uses an 8-hour reference.",
            color = PulsePalette.muted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun WeekChart(
    report: SleepReport,
    title: String,
    tag: String,
    style: ChartStyle,
    primary: (SleepWeekPoint) -> Double?,
    onOpenMetric: (String, LocalDate) -> Unit,
    secondary: (SleepWeekPoint) -> Double? = { _ -> null },
    floorAtZero: Boolean = true,
) {
    val selected = report.week.lastOrNull { it.selected }?.date
    Column(
        Modifier
            .fillMaxWidth()
            .background(PulsePalette.surface, RoundedCornerShape(18.dp))
            .padding(PulseSpace.md),
    ) {
        Text(title, color = PulsePalette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
        val marks = report.week.map { point ->
            ChartMark(
                label = point.label,
                primary = primary(point)?.toFloat(),
                secondary = secondary(point)?.toFloat(),
                highlighted = point.selected,
            )
        }
        val values = marks.flatMap { listOfNotNull(it.primary, it.secondary) }
        val (low, high) = if (style == ChartStyle.FLOATING) {
            8f * 60f to 22f * 60f
        } else {
            axisBounds(values, floorAtZero)
        }
        TrendChart(
            points = marks,
            style = style,
            yMin = low,
            yMax = high,
            tag = "week_$tag",
            onClick = {
                if (selected != null) onOpenMetric(tag, selected)
            },
        )
    }
}

@Composable
private fun MetricSurface(title: String, tag: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(PulsePalette.surface, RoundedCornerShape(18.dp))
            .padding(PulseSpace.md)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(PulseSpace.sm),
        content = { 
            Text(title, color = PulsePalette.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
            content()
        },
    )
}

@Composable
private fun ComparePercent(current: Int?, prior: Int?, lowerIsBetter: Boolean = false) {
    val tone = when {
        current == null || prior == null || current == prior -> PulsePalette.text
        lowerIsBetter && current < prior -> PulsePalette.green
        lowerIsBetter && current > prior -> PulsePalette.yellow
        !lowerIsBetter && current > prior -> PulsePalette.green
        else -> PulsePalette.yellow
    }
    Text(
        if (current == null) "--" else "$current%",
        color = tone,
        fontSize = 32.sp,
        fontWeight = FontWeight.SemiBold,
    )
    if (prior != null) {
        Text("$prior%", color = PulsePalette.muted, fontSize = PulseType.body)
    }
}

private fun SleepBand?.color(): Color = when (this) {
    SleepBand.POOR -> PulsePalette.orange
    SleepBand.SUFFICIENT -> PulsePalette.muted
    SleepBand.OPTIMAL -> PulsePalette.green
    null -> PulsePalette.track
}

private fun titleCase(label: String): String =
    label.split(" ").joinToString(" ") { word ->
        word.lowercase(java.util.Locale.US).replaceFirstChar { it.uppercase() }
    }

internal fun formatClockDuration(seconds: Long): String {
    val whole = seconds.coerceAtLeast(0L)
    val minutes = (whole % 3600) / 60
    return "${whole / 3600}:${minutes.toString().padStart(2, '0')}"
}

private fun signedClock(seconds: Int): String {
    val sign = if (seconds > 0) "+" else ""
    return sign + formatClockDuration(seconds.toLong())
}
