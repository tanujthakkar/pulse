package app.pulse.feature.home

import app.pulse.metrics.BodyProfile
import app.pulse.metrics.ChangeTone
import app.pulse.metrics.ChartRange
import app.pulse.metrics.DatedValue
import app.pulse.metrics.DayResult
import app.pulse.metrics.DayRules
import app.pulse.metrics.MetricDirection
import app.pulse.metrics.MetricSeries
import app.pulse.metrics.PlotPoint
import app.pulse.metrics.changeTone
import app.pulse.metrics.estimatedDayKcal
import app.pulse.metrics.rangedSeries
import app.pulse.metrics.sleepConsistencyPercent
import app.pulse.metrics.sleepEfficiencyPercent
import app.pulse.metrics.sleepNeedPercent
import app.pulse.metrics.uthVo2Max
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

internal data class MetricSpec(
    val title: String,
    val direction: MetricDirection,
    val note: String?,
    val format: (Double) -> String,
    val value: (DayResult, List<DayResult>, BodyProfile?) -> Double?,
)

internal fun metricSpec(tag: String, zone: ZoneId): MetricSpec? = when (tag) {
    "dash_hrv" -> MetricSpec("HEART RATE VARIABILITY", MetricDirection.HIGHER_BETTER, "ms", ::integer) { day, _, _ ->
        day.rhythm?.rmssdMs
    }
    "dash_rhr" -> MetricSpec("RESTING HEART RATE", MetricDirection.LOWER_BETTER, "bpm", ::integer) { day, _, _ ->
        day.rhythm?.restingHr?.toDouble()
    }
    "dash_avg_hr" -> MetricSpec("AVERAGE HEART RATE", MetricDirection.LOWER_BETTER, "bpm", ::integer) { day, _, _ ->
        day.meanHeartBpm
    }
    "dash_steps" -> MetricSpec("STEPS", MetricDirection.HIGHER_BETTER, null, ::grouped) { day, _, _ ->
        (day.stepIncrease + day.edgeStepIncrease).toDouble()
    }
    "dash_sleep_debt" -> MetricSpec(
        "SLEEP DEBT",
        MetricDirection.LOWER_BETTER,
        "Against an 8-hour reference",
        { formatSignedHm(it.roundToInt()) },
    ) { day, _, _ ->
        day.main?.sleepSeconds?.let { (DayRules.SLEEP_REFERENCE_SECONDS - it).toDouble() }
    }
    "dash_vo2" -> MetricSpec(
        "VO2 MAX",
        MetricDirection.HIGHER_BETTER,
        "Uth estimate from age and resting heart rate",
        { String.format(Locale.US, "%.1f", it) },
    ) { day, _, profile ->
        val age = profile?.ageYears ?: return@MetricSpec null
        val resting = day.rhythm?.restingHr ?: return@MetricSpec null
        uthVo2Max(age, resting)
    }
    "dash_calories" -> MetricSpec(
        "CALORIES",
        MetricDirection.NEUTRAL,
        "Mifflin–St Jeor plus Keytel above resting",
        ::grouped,
    ) { day, _, profile ->
        if (profile == null || !profile.valid()) null
        else estimatedDayKcal(profile, day.meanHeartBpm, day.heartSeconds, day.rhythm?.restingHr)
    }
    "sleep_performance", "sleep_hours_vs_needed" -> MetricSpec(
        if (tag == "sleep_performance") "SLEEP PERFORMANCE" else "HOURS VS. NEEDED",
        MetricDirection.HIGHER_BETTER,
        "Time asleep divided by estimated sleep need",
        ::integer,
    ) { day, history, _ ->
        val slept = day.main?.sleepSeconds ?: return@MetricSpec null
        val need = daySleepNeed(history, day.date) ?: return@MetricSpec null
        sleepNeedPercent(slept, need.totalSeconds)?.toDouble()
    }
    "sleep_hours" -> MetricSpec("HOURS OF SLEEP", MetricDirection.HIGHER_BETTER, null, ::hoursClock) { day, _, _ ->
        day.main?.sleepSeconds?.toDouble()
    }
    "sleep_restorative" -> MetricSpec(
        "RESTORATIVE SLEEP",
        MetricDirection.HIGHER_BETTER,
        "Estimated deep sleep plus REM",
        ::hoursClock,
    ) { _, _, _ -> null }
    "sleep_consistency" -> MetricSpec(
        "SLEEP CONSISTENCY",
        MetricDirection.HIGHER_BETTER,
        "Bed and wake deviation from your median",
        ::integer,
    ) { day, history, _ ->
        val nights = history.filter { !it.date.isAfter(day.date) }.mapNotNull { it.main }.takeLast(5)
        if (nights.size < 3) null
        else sleepConsistencyPercent(
            nights.map { minutesFromNoon(it.startUnix, zone) },
            nights.map { minutesFromNoon(it.endUnix, zone) },
        )?.toDouble()
    }
    "sleep_time_in_bed" -> MetricSpec("TIME IN BED", MetricDirection.HIGHER_BETTER, null, ::hoursClock) { day, _, _ ->
        day.main?.spanSeconds?.toDouble()
    }
    "sleep_efficiency" -> MetricSpec("SLEEP EFFICIENCY", MetricDirection.HIGHER_BETTER, null, ::integer) { day, _, _ ->
        val main = day.main ?: return@MetricSpec null
        sleepEfficiencyPercent(main.sleepSeconds, main.spanSeconds)?.toDouble()
    }
    "sleep_debt" -> MetricSpec(
        "SLEEP DEBT",
        MetricDirection.LOWER_BETTER,
        "Estimated need minus time asleep",
        ::hoursClock,
    ) { day, history, _ ->
        val slept = day.main?.sleepSeconds ?: return@MetricSpec null
        val need = daySleepNeed(history, day.date) ?: return@MetricSpec null
        (need.totalSeconds - slept).coerceAtLeast(0).toDouble()
    }
    else -> null
}

internal fun daySleepNeed(history: List<DayResult>, date: LocalDate) = needFor(history, date)

data class MetricDetailState(
    val title: String,
    val note: String?,
    val range: ChartRange,
    val points: List<PlotPoint>,
    val averageText: String,
    val changeText: String?,
    val tone: ChangeTone,
    val bandLow: Double?,
    val bandHigh: Double?,
    val line: Boolean,
    val yMin: Double,
    val yMax: Double,
    val unit: String?,
)

internal fun presentSeries(spec: MetricSpec, series: MetricSeries, range: ChartRange): MetricDetailState {
    val values = series.points.mapNotNull { it.value }
    val band = listOfNotNull(series.bandLow, series.bandHigh)
    val axis = axisBounds((values + band).map { it.toFloat() }, floorAtZero = true)
    return MetricDetailState(
        title = spec.title,
        note = spec.note,
        range = range,
        points = series.points,
        averageText = series.average?.let(spec.format) ?: "--",
        changeText = series.changePercent?.let { change ->
            val sign = if (change > 0) "+" else ""
            "$sign${change.roundToInt()}% vs previous"
        },
        tone = changeTone(series.changePercent, series.direction),
        bandLow = series.bandLow,
        bandHigh = series.bandHigh,
        line = series.line,
        yMin = axis.first.toDouble(),
        yMax = axis.second.toDouble(),
        unit = spec.note?.takeIf { it.length <= 4 },
    )
}

internal fun seriesFor(
    spec: MetricSpec,
    history: List<DayResult>,
    end: LocalDate,
    range: ChartRange,
    profile: BodyProfile?,
    overrides: Map<LocalDate, Double?> = emptyMap(),
): MetricSeries {
    val dated = history.map { day ->
        DatedValue(day.date, overrides[day.date] ?: spec.value(day, history, profile))
    }
    return rangedSeries(dated, end, range, spec.direction)
}

private fun integer(value: Double): String = value.roundToInt().toString()

private fun grouped(value: Double): String = String.format(Locale.US, "%,d", value.roundToInt())

private fun hoursClock(seconds: Double): String = formatClockDuration(seconds.toLong())
