package app.pulse.metrics

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Published estimates that need a body profile.
 *
 * VO2 max is Uth–Sørensen–Overgaard–Pedersen 2004, with Tanaka 2001 for
 * max heart rate. Energy is Mifflin–St Jeor resting expenditure plus the
 * Keytel 2005 heart-rate cost above resting. The formulas and their limits
 * are written up in docs/published-metrics.md.
 */
enum class Sex { FEMALE, MALE }

data class BodyProfile(
    val sex: Sex,
    val ageYears: Int,
    val heightCm: Int,
    val weightKg: Double,
) {
    fun valid(): Boolean =
        ageYears in 15..100 && heightCm in 120..230 && weightKg in 30.0..250.0
}

/** 208 − 0.7 × age. Tanaka, Monahan, and Seals, 2001. */
fun tanakaMaxHeartRate(ageYears: Int): Double = 208.0 - 0.7 * ageYears

/**
 * 15.3 × HRmax / HRrest.
 *
 * Returns null when age or resting heart rate cannot support the ratio.
 */
fun uthVo2Max(ageYears: Int, restingHr: Int): Double? {
    if (ageYears !in 15..100 || restingHr <= 0) return null
    val maxHr = tanakaMaxHeartRate(ageYears)
    if (maxHr <= 0.0) return null
    return 15.3 * maxHr / restingHr
}

/** kcal/day. Mifflin et al., 1990. */
fun mifflinStJeorBmr(profile: BodyProfile): Double {
    val base = 10.0 * profile.weightKg + 6.25 * profile.heightCm - 5.0 * profile.ageYears
    return if (profile.sex == Sex.MALE) base + 5.0 else base - 161.0
}

/**
 * Keytel et al. 2005, kJ per minute at one heart rate.
 *
 * The published fit is for submaximal exercise. Pulse uses only the
 * difference above resting, where the age and weight terms cancel.
 */
fun keytelKjPerMinute(profile: BodyProfile, heartRate: Double): Double =
    if (profile.sex == Sex.MALE) {
        -55.0969 + 0.6309 * heartRate + 0.1988 * profile.weightKg + 0.2017 * profile.ageYears
    } else {
        -20.4022 + 0.4472 * heartRate - 0.1263 * profile.weightKg + 0.074 * profile.ageYears
    }

/** kcal per minute of the Keytel difference between [heartRate] and resting. */
fun keytelIncrementalKcalPerMinute(profile: BodyProfile, heartRate: Double, restingHr: Int): Double {
    if (restingHr <= 0) return 0.0
    val deltaKj = keytelKjPerMinute(profile, heartRate) - keytelKjPerMinute(profile, restingHr.toDouble())
    return (deltaKj / 4.184).coerceAtLeast(0.0)
}

/**
 * One day's energy: the Mifflin resting cost for 24 hours, plus Keytel's
 * incremental cost for the minutes that have a heart-rate sample.
 *
 * [meanHr] is the mean of those samples. Minutes at or below resting add
 * nothing above the resting cost.
 */
fun estimatedDayKcal(profile: BodyProfile, meanHr: Double?, heartSeconds: Int, restingHr: Int?): Double {
    val bmr = mifflinStJeorBmr(profile)
    if (meanHr == null || restingHr == null || heartSeconds <= 0) return bmr
    val perMinute = keytelIncrementalKcalPerMinute(profile, meanHr, restingHr)
    return bmr + perMinute * (heartSeconds / 60.0)
}

/**
 * Sleep need as three public pieces: a personal baseline, a strain addition,
 * and a portion of recent debt.
 *
 * The coefficients are Pulse's. They are a bounded reading of the published
 * description, not a vendor formula. See docs/published-metrics.md.
 */
data class SleepNeed(
    val baselineSeconds: Int,
    val strainSeconds: Int,
    val debtSeconds: Int,
) {
    val totalSeconds: Int = (baselineSeconds + strainSeconds + debtSeconds).coerceAtLeast(0)
}

fun personalSleepBaselineSeconds(priorSleepSeconds: List<Int>): Int {
    val recent = priorSleepSeconds.filter { it > 0 }.takeLast(14)
    if (recent.size < 3) return DayRules.SLEEP_REFERENCE_SECONDS
    return medianInt(recent)
}

/** Up to 60 minutes, linear in an approximate 0–21 strain. */
fun strainSleepAdjustmentSeconds(strain21: Double?): Int {
    if (strain21 == null) return 0
    val fraction = (strain21 / 21.0).coerceIn(0.0, 1.0)
    return (fraction * 60.0 * 60.0).roundToInt()
}

/**
 * Half of the recent shortfall against the baseline, capped at 90 minutes.
 *
 * [shortfalls] are max(0, baseline − slept) for earlier nights.
 */
fun sleepDebtAdditionSeconds(shortfalls: List<Int>): Int {
    val recent = shortfalls.takeLast(4).sumOf { it.coerceAtLeast(0) }
    return (recent * 0.5).roundToInt().coerceIn(0, 90 * 60)
}

fun sleepNeed(priorSleepSeconds: List<Int>, strain21: Double?, shortfalls: List<Int>): SleepNeed {
    val baseline = personalSleepBaselineSeconds(priorSleepSeconds)
    return SleepNeed(
        baselineSeconds = baseline,
        strainSeconds = strainSleepAdjustmentSeconds(strain21),
        debtSeconds = sleepDebtAdditionSeconds(shortfalls),
    )
}

/** Asleep samples divided by [needSeconds], as a percent. Null when need is empty. */
fun sleepNeedPercent(asleepSeconds: Int, needSeconds: Int): Int? {
    if (asleepSeconds < 0 || needSeconds <= 0) return null
    return (asleepSeconds * 100.0 / needSeconds).roundToInt().coerceAtLeast(0)
}

/**
 * Bed and wake regularity over a 3-hour scale.
 *
 * 100 means every night matches the median bed and wake. Each minute of mean
 * absolute deviation from those medians costs 1/180 of the score. Fewer than
 * three nights has no score. This is not the Sleep Regularity Index.
 */
fun sleepConsistencyPercent(bedMinutes: List<Int>, wakeMinutes: List<Int>): Int? {
    if (bedMinutes.size < 3 || bedMinutes.size != wakeMinutes.size) return null
    val bedMedian = medianInt(bedMinutes)
    val wakeMedian = medianInt(wakeMinutes)
    val bedMad = bedMinutes.map { abs(it - bedMedian) }.average()
    val wakeMad = wakeMinutes.map { abs(it - wakeMedian) }.average()
    val deviation = (bedMad + wakeMad) / 2.0
    return ((1.0 - deviation / 180.0) * 100.0).roundToInt().coerceIn(0, 100)
}

/** Asleep samples divided by the in-bed span, as a percent. */
fun sleepEfficiencyPercent(asleepSeconds: Int, spanSeconds: Long): Int? {
    if (asleepSeconds < 0 || spanSeconds <= 0L) return null
    return (asleepSeconds * 100.0 / spanSeconds).roundToInt().coerceIn(0, 100)
}

enum class SleepStage { AWAKE, LIGHT, DEEP, REM }

data class StageEstimate(
    val available: Boolean,
    val minutes: List<SleepStage>,
    val note: String,
) {
    companion object {
        fun unavailable(): StageEstimate = StageEstimate(
            available = false,
            minutes = emptyList(),
            note = "Estimated stages are not available for this night.",
        )
    }
}

/**
 * Minute-level stage estimate from heart rate and sleep motion.
 *
 * Awake minutes are the ones that are mostly not sleep, or whose heart rate
 * sits more than 12 bpm above resting. Among the sleeping minutes, the
 * lowest quarter of heart rates is deep sleep, the highest quarter that also
 * moves by at least 4 bpm inside the minute is REM, and the rest is light.
 * Fewer than 30 minutes of samples is not enough, and the result is then
 * unavailable rather than filled in. This is not a published staging model.
 */
fun estimateSleepStages(
    records: List<SecondRecord>,
    start: Long,
    end: Long,
    restingHr: Int?,
): StageEstimate {
    if (end < start) return StageEstimate.unavailable()
    val window = records.filter { it.unixSeconds in start..end }.sortedBy { it.unixSeconds }
    val byMinute = window.groupBy { it.unixSeconds / 60L }
    if (byMinute.size < 30) return StageEstimate.unavailable()
    val rows = byMinute.toSortedMap().values.map { minute ->
        val hearts = minute.mapNotNull { it.heartRateBpm }
        val asleepFraction = minute.count { it.bandMotion == DayRules.SLEEP || it.edgeWindowSleep }.toDouble() / minute.size
        MinuteRow(
            meanHr = if (hearts.isEmpty()) null else hearts.average(),
            range = if (hearts.size < 2) 0 else hearts.max() - hearts.min(),
            mostlyAsleep = asleepFraction >= 0.5,
        )
    }
    val rest = restingHr?.toDouble()
        ?: rows.mapNotNull { it.meanHr }.sorted().let { sorted ->
            if (sorted.isEmpty()) null else sorted[(sorted.size * 0.1).toInt().coerceAtMost(sorted.lastIndex)]
        }
    val awake = rows.map { row ->
        val elevated = rest != null && row.meanHr != null && row.meanHr > rest + 12.0
        !row.mostlyAsleep || elevated || row.meanHr == null
    }
    val sleepHrs = rows.zip(awake).filter { !it.second }.mapNotNull { it.first.meanHr }.sorted()
    if (sleepHrs.isEmpty()) {
        return StageEstimate(true, List(rows.size) { SleepStage.AWAKE }, ESTIMATED_STAGE_NOTE)
    }
    val deepCut = percentile(sleepHrs, 0.25)
    val remCut = percentile(sleepHrs, 0.75)
    val stages = rows.zip(awake).map { (row, isAwake) ->
        val hr = row.meanHr
        when {
            isAwake || hr == null -> SleepStage.AWAKE
            hr <= deepCut -> SleepStage.DEEP
            hr >= remCut && row.range >= 4 -> SleepStage.REM
            else -> SleepStage.LIGHT
        }
    }
    return StageEstimate(true, stages, ESTIMATED_STAGE_NOTE)
}

private data class MinuteRow(val meanHr: Double?, val range: Int, val mostlyAsleep: Boolean)

const val ESTIMATED_STAGE_NOTE: String =
    "Stages are estimated from heart rate and motion. They are not measured stages."

/**
 * A wake event is a run of non-sleep of at least 30 seconds after sleep has
 * started and before sleep returns.
 *
 * The final wake-up is the end of the night, not an event.
 */
fun wakeEventCount(records: List<SecondRecord>, start: Long, end: Long): Int {
    val window = records.filter { it.unixSeconds in start..end }.sortedBy { it.unixSeconds }
    var events = 0
    var seenSleep = false
    var runStart: Long? = null
    var runEnd: Long? = null
    fun close() {
        val from = runStart
        val to = runEnd
        if (seenSleep && from != null && to != null && to - from + 1 >= 30L) events += 1
        runStart = null
        runEnd = null
    }
    for (sample in window) {
        val asleep = sample.bandMotion == DayRules.SLEEP || sample.edgeWindowSleep
        if (asleep) {
            close()
            seenSleep = true
        } else if (seenSleep) {
            if (runStart == null) runStart = sample.unixSeconds
            runEnd = sample.unixSeconds
        }
    }
    return events
}

/**
 * 0–3 sleep-stress samples across the night.
 *
 * Each minute is heart-rate elevation above resting, in steps of about 8 bpm,
 * and an awake minute is lifted by one. The result is estimated.
 */
fun sleepStressSeries(records: List<SecondRecord>, start: Long, end: Long, restingHr: Int?): List<Float> {
    if (end < start) return emptyList()
    val window = records.filter { it.unixSeconds in start..end }
    if (window.isEmpty()) return emptyList()
    val byMinute = window.groupBy { it.unixSeconds / 60L }.toSortedMap()
    val rest = restingHr?.toDouble() ?: byMinute.values.mapNotNull { minute ->
        minute.mapNotNull { it.heartRateBpm }.average().takeIf { !it.isNaN() }
    }.minOrNull() ?: return emptyList()
    return byMinute.values.map { minute ->
        val hearts = minute.mapNotNull { it.heartRateBpm }
        val hr = if (hearts.isEmpty()) rest else hearts.average()
        val asleep = minute.count { it.bandMotion == DayRules.SLEEP || it.edgeWindowSleep } * 2 >= minute.size
        val elevation = ((hr - rest) / 8.0).coerceIn(0.0, 3.0)
        (if (asleep) elevation else (elevation + 1.0).coerceAtMost(3.0)).toFloat()
    }
}

enum class StressLevel { HIGH, MEDIUM, LOW }

fun stressLevel(value: Float): StressLevel = when {
    value >= 2f -> StressLevel.HIGH
    value >= 1f -> StressLevel.MEDIUM
    else -> StressLevel.LOW
}

enum class MetricDirection { HIGHER_BETTER, LOWER_BETTER, NEUTRAL }

enum class ChartRange { WEEK, MONTH, SIX_MONTH }

data class DatedValue(
    val date: LocalDate,
    val value: Double?,
)

data class PlotPoint(
    val label: String,
    val value: Double?,
    val highlighted: Boolean = false,
)

/**
 * A chart range, its average, and the change against the previous range.
 *
 * The shaded band is the mean of the plotted points ± one population
 * standard deviation. It is omitted when fewer than two points are plotted.
 */
data class MetricSeries(
    val points: List<PlotPoint>,
    val average: Double?,
    val changePercent: Double?,
    val direction: MetricDirection,
    val bandLow: Double?,
    val bandHigh: Double?,
    val line: Boolean,
)

enum class ChangeTone { IMPROVED, WORSENED, NEUTRAL }

fun changeTone(changePercent: Double?, direction: MetricDirection): ChangeTone {
    if (changePercent == null || direction == MetricDirection.NEUTRAL || changePercent == 0.0) {
        return ChangeTone.NEUTRAL
    }
    val up = changePercent > 0.0
    val improved = (direction == MetricDirection.HIGHER_BETTER && up) ||
        (direction == MetricDirection.LOWER_BETTER && !up)
    return if (improved) ChangeTone.IMPROVED else ChangeTone.WORSENED
}

fun rangedSeries(
    dated: List<DatedValue>,
    end: LocalDate,
    range: ChartRange,
    direction: MetricDirection,
): MetricSeries {
    val start = rangeStart(end, range)
    val length = ChronoUnit.DAYS.between(start, end) + 1
    val previousEnd = start.minusDays(1)
    val previousStart = previousEnd.minusDays(length - 1)
    val byDate = dated.associate { it.date to it.value }
    val average = averageBetween(byDate, start, end)
    val previous = averageBetween(byDate, previousStart, previousEnd)
    val change = if (average != null && previous != null && previous != 0.0) {
        (average - previous) / abs(previous) * 100.0
    } else {
        null
    }
    val points = when (range) {
        ChartRange.WEEK -> datesBetween(start, end).map { date ->
            PlotPoint(weekLabel(date), byDate[date], date == end)
        }
        ChartRange.MONTH -> monthPoints(byDate, start, end)
        ChartRange.SIX_MONTH -> datesBetween(start, end).mapNotNull { date ->
            val value = byDate[date] ?: return@mapNotNull null
            val label = if (date.dayOfMonth == 1 || date == end) shortDate(date) else ""
            PlotPoint(label, value, date == end)
        }
    }
    val plotted = points.mapNotNull { it.value }
    val band = typicalBand(plotted)
    return MetricSeries(
        points = points,
        average = average,
        changePercent = change,
        direction = direction,
        bandLow = band?.first,
        bandHigh = band?.second,
        line = range == ChartRange.SIX_MONTH,
    )
}

/** Mean ± one population standard deviation. Null when fewer than two values. */
fun typicalBand(values: List<Double>): Pair<Double, Double>? {
    if (values.size < 2) return null
    val mean = values.average()
    val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
    val sd = sqrt(variance)
    return mean - sd to mean + sd
}

private fun rangeStart(end: LocalDate, range: ChartRange): LocalDate = when (range) {
    ChartRange.WEEK -> end.minusDays(6)
    ChartRange.MONTH -> end.minusDays(27)
    ChartRange.SIX_MONTH -> end.minusDays(182)
}

private fun averageBetween(byDate: Map<LocalDate, Double?>, start: LocalDate, end: LocalDate): Double? {
    val values = datesBetween(start, end).mapNotNull { byDate[it] }
    return if (values.isEmpty()) null else values.average()
}

private fun monthPoints(byDate: Map<LocalDate, Double?>, start: LocalDate, end: LocalDate): List<PlotPoint> {
    val points = ArrayList<PlotPoint>()
    var monday = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    while (!monday.isAfter(end)) {
        val weekEnd = monday.plusDays(6)
        val from = if (monday.isBefore(start)) start else monday
        val to = if (weekEnd.isAfter(end)) end else weekEnd
        val values = datesBetween(from, to).mapNotNull { byDate[it] }
        val coversEnd = !end.isBefore(monday) && !end.isAfter(weekEnd)
        points += PlotPoint(
            label = shortDate(monday),
            value = if (values.isEmpty()) null else values.average(),
            highlighted = coversEnd,
        )
        monday = monday.plusWeeks(1)
    }
    return points
}

private fun datesBetween(start: LocalDate, end: LocalDate): List<LocalDate> {
    if (end.isBefore(start)) return emptyList()
    val count = ChronoUnit.DAYS.between(start, end).toInt() + 1
    return List(count) { start.plusDays(it.toLong()) }
}

private fun weekLabel(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("EEE", Locale.US)) + "\n" + date.dayOfMonth

private fun shortDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("M/d", Locale.US))

private fun percentile(sorted: List<Double>, fraction: Double): Double {
    if (sorted.isEmpty()) return 0.0
    val index = ((sorted.size - 1) * fraction).roundToInt().coerceIn(0, sorted.lastIndex)
    return sorted[index]
}

internal fun medianInt(values: List<Int>): Int {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}
