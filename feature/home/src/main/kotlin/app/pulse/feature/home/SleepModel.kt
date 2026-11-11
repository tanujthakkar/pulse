package app.pulse.feature.home

import app.pulse.data.DayMetricsSource
import app.pulse.metrics.DayResult
import app.pulse.metrics.DayRules
import app.pulse.metrics.SecondRecord
import app.pulse.metrics.SleepEpisode
import app.pulse.metrics.SleepStage
import app.pulse.metrics.StageEstimate
import app.pulse.metrics.StressLevel
import app.pulse.metrics.approximateStrain21
import app.pulse.metrics.estimateSleepStages
import app.pulse.metrics.sleepConsistencyPercent
import app.pulse.metrics.sleepEfficiencyPercent
import app.pulse.metrics.sleepNeed
import app.pulse.metrics.sleepNeedPercent
import app.pulse.metrics.sleepStressSeries
import app.pulse.metrics.stressLevel
import app.pulse.metrics.wakeEventCount
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Display bands for a percent on the sleep page.
 *
 * Below 70 is poor, from 70 up to but not including 85 is sufficient, and 85
 * and above is optimal. A lower-is-better percent, such as high sleep stress,
 * is flipped before it is banded. These cuts are a Pulse scale so a bar can
 * be read at a glance. They are not a published product cutoff.
 */
enum class SleepBand { POOR, SUFFICIENT, OPTIMAL }

fun sleepBand(percent: Int, lowerIsBetter: Boolean = false): SleepBand {
    val score = if (lowerIsBetter) 100 - percent else percent
    return when {
        score < 70 -> SleepBand.POOR
        score < 85 -> SleepBand.SUFFICIENT
        else -> SleepBand.OPTIMAL
    }
}

data class BreakdownRow(
    val name: String,
    val percent: Int?,
    val band: SleepBand?,
    val tag: String,
)

data class StageSlice(
    val seconds: Long,
    val percent: Int,
    val typicalPercent: Int?,
)

data class StageSummary(
    val awake: StageSlice,
    val light: StageSlice,
    val deep: StageSlice,
    val rem: StageSlice,
)

data class ConsistencyDay(
    val label: String,
    val bedMinute: Int,
    val wakeMinute: Int,
    val selected: Boolean,
)

data class ConsistencyView(
    val percent: Int?,
    val priorPercent: Int?,
    val days: List<ConsistencyDay>,
    val optimalBed: Int?,
    val optimalWake: Int?,
)

data class SleepWeekPoint(
    val date: LocalDate,
    val label: String,
    val selected: Boolean,
    val performance: Double?,
    val asleepHours: Double?,
    val neededHours: Double?,
    val vsNeededPercent: Double?,
    val deepHours: Double?,
    val remHours: Double?,
    val consistency: Double?,
    val bedMinute: Double?,
    val wakeMinute: Double?,
    val efficiency: Double?,
    val debtHours: Double?,
)

data class SleepReport(
    val dateLabel: String,
    val batteryPercent: Int?,
    val performancePercent: Int?,
    val band: SleepBand?,
    val breakdown: List<BreakdownRow>,
    val asleepSeconds: Long,
    val asleepAverageSeconds: Long?,
    val heart: List<Int>,
    val onsetLabel: String,
    val wakeLabel: String,
    val inBedSeconds: Long,
    val stages: StageSummary?,
    val stageNote: String,
    val restorativeSeconds: Long?,
    val baselineSeconds: Int,
    val strainSeconds: Int,
    val debtSeconds: Int,
    val needSeconds: Int,
    val needPercent: Int?,
    val priorNeedPercent: Int?,
    val consistency: ConsistencyView,
    val efficiencyPercent: Int?,
    val priorEfficiency: Int?,
    val awakeSeconds: Long,
    val wakeEvents: Int,
    val asleepTimeline: List<Boolean>,
    val stress: List<Float>,
    val stressHighSeconds: Long,
    val stressMediumSeconds: Long,
    val stressLowSeconds: Long,
    val highStressPercent: Int?,
    val week: List<SleepWeekPoint>,
)

/**
 * Reads one night off the caller thread and builds the sleep page.
 *
 * The open night is one window. Up to six earlier nights in the same week
 * are read the same way so restorative bars are estimated from samples, then
 * those samples are dropped. A cached day book is reused and is not rebuilt.
 */
internal fun loadSleepReport(
    start: Long,
    end: Long,
    days: DayMetricsSource,
): SleepReport {
    val cached = days.cachedSnapshot()
    val snapshot = cached ?: days.snapshot()
    val zone = zoneOf(snapshot.book.zone)
    val records = days.windowRecords(start, end + 1)
    val wakeDate = Instant.ofEpochSecond(end).atZone(zone).toLocalDate()
    val weekStart = wakeDate.minusDays(6)
    val extras = LinkedHashMap<LocalDate, List<SecondRecord>>()
    for (day in snapshot.book.days) {
        val main = day.main ?: continue
        if (day.date.isBefore(weekStart) || day.date.isAfter(wakeDate) || day.date == wakeDate) continue
        extras[day.date] = days.windowRecords(main.startUnix, main.endUnix + 1)
    }
    return buildSleepReport(
        start = start,
        end = end,
        zone = zone,
        records = records,
        history = snapshot.book.days,
        wakeDate = wakeDate,
        batteryPercent = snapshot.batteryPercent,
        extraNights = extras,
    )
}

fun buildSleepReport(
    start: Long,
    end: Long,
    zone: ZoneId,
    records: List<SecondRecord>,
    history: List<DayResult>,
    wakeDate: LocalDate,
    batteryPercent: Int?,
    extraNights: Map<LocalDate, List<SecondRecord>> = emptyMap(),
): SleepReport {
    val window = records.filter { it.unixSeconds in start..end }
    val asleep = window.count { it.bandMotion == DayRules.SLEEP || it.edgeWindowSleep }.toLong()
    val span = (end - start + 1).coerceAtLeast(1L)
    val resting = history.firstOrNull { it.date == wakeDate }?.rhythm?.restingHr
        ?: history.filter { it.date.isBefore(wakeDate) }.mapNotNull { it.rhythm?.restingHr }.lastOrNull()
    val stages = estimateSleepStages(window, start, end, resting)
    val stageSeconds = stageSeconds(window, stages)
    val stressMinutes = sleepStressSeries(window, start, end, resting)
    val high = stressMinutes.count { stressLevel(it) == StressLevel.HIGH }
    val medium = stressMinutes.count { stressLevel(it) == StressLevel.MEDIUM }
    val low = stressMinutes.count { stressLevel(it) == StressLevel.LOW }
    val minuteCount = stressMinutes.size.coerceAtLeast(1)
    val highPercent = if (stressMinutes.isEmpty()) null else (high * 100.0 / stressMinutes.size).roundToInt()
    val efficiency = sleepEfficiencyPercent(asleep.toInt(), span)
    val need = needFor(history, wakeDate)
    val performance = need?.let { sleepNeedPercent(asleep.toInt(), it.totalSeconds) }
    val consistency = consistencyView(history, wakeDate, zone)
    val prior = history.filter { it.date.isBefore(wakeDate) && !it.date.isBefore(wakeDate.minusDays(30)) }
    val asleepAverage = averageOfLong(prior.mapNotNull { it.main?.sleepSeconds?.toLong() })?.toLong()
    val priorPerformance = averageOfInt(prior.mapNotNull { day ->
        val slept = day.main?.sleepSeconds ?: return@mapNotNull null
        needFor(history, day.date).let { sleepNeedPercent(slept, it.totalSeconds) }
    })?.roundToInt()
    val priorEfficiency = averageOfInt(prior.mapNotNull { day ->
        val main = day.main ?: return@mapNotNull null
        sleepEfficiencyPercent(main.sleepSeconds, main.spanSeconds)
    })?.roundToInt()
    val week = weekPoints(history, wakeDate, zone, window, extraNights, resting)
    val restorative = stageSeconds?.let { it.deep + it.rem }
    return SleepReport(
        dateLabel = DATE_LABEL.format(wakeDate).uppercase(Locale.US),
        batteryPercent = batteryPercent,
        performancePercent = performance,
        band = performance?.let { sleepBand(it) },
        breakdown = listOf(
            BreakdownRow("HOURS VS. NEEDED", performance, performance?.let { sleepBand(it) }, "sleep_hours_vs_needed"),
            BreakdownRow(
                "SLEEP CONSISTENCY",
                consistency.percent,
                consistency.percent?.let { sleepBand(it) },
                "sleep_consistency",
            ),
            BreakdownRow(
                "SLEEP EFFICIENCY",
                efficiency,
                efficiency?.let { sleepBand(it) },
                "sleep_efficiency",
            ),
            BreakdownRow(
                "HIGH SLEEP STRESS",
                highPercent,
                highPercent?.let { sleepBand(it, lowerIsBetter = true) },
                "sleep_stress",
            ),
        ),
        asleepSeconds = asleep,
        asleepAverageSeconds = asleepAverage,
        heart = downsampleInts(window.mapNotNull { it.heartRateBpm }, 120),
        onsetLabel = CLOCK.format(Instant.ofEpochSecond(start).atZone(zone)),
        wakeLabel = CLOCK.format(Instant.ofEpochSecond(end).atZone(zone)),
        inBedSeconds = span,
        stages = stageSeconds?.let { seconds ->
            val total = (seconds.awake + seconds.light + seconds.deep + seconds.rem).coerceAtLeast(1L)
            val typical = typicalStagePercents(history, wakeDate, extraNights)
            StageSummary(
                awake = slice(seconds.awake, total, typical?.awake),
                light = slice(seconds.light, total, typical?.light),
                deep = slice(seconds.deep, total, typical?.deep),
                rem = slice(seconds.rem, total, typical?.rem),
            )
        },
        stageNote = stages.note,
        restorativeSeconds = restorative,
        baselineSeconds = need?.baselineSeconds ?: DayRules.SLEEP_REFERENCE_SECONDS,
        strainSeconds = need?.strainSeconds ?: 0,
        debtSeconds = need?.debtSeconds ?: 0,
        needSeconds = need?.totalSeconds ?: DayRules.SLEEP_REFERENCE_SECONDS,
        needPercent = performance,
        priorNeedPercent = priorPerformance,
        consistency = consistency,
        efficiencyPercent = efficiency,
        priorEfficiency = priorEfficiency,
        awakeSeconds = (span - asleep).coerceAtLeast(0L),
        wakeEvents = wakeEventCount(window, start, end),
        asleepTimeline = downsampleFlags(window.map { it.bandMotion == DayRules.SLEEP || it.edgeWindowSleep }, 48),
        stress = downsampleFloats(stressMinutes, 120),
        stressHighSeconds = high * 60L,
        stressMediumSeconds = medium * 60L,
        stressLowSeconds = low * 60L,
        highStressPercent = highPercent,
        week = week,
    ).let { report ->
        report.copy(
            stressHighSeconds = if (stressMinutes.isEmpty()) 0L else high * (span / minuteCount),
            stressMediumSeconds = if (stressMinutes.isEmpty()) 0L else medium * (span / minuteCount),
            stressLowSeconds = if (stressMinutes.isEmpty()) 0L else low * (span / minuteCount),
        )
    }
}

private data class StageSeconds(val awake: Long, val light: Long, val deep: Long, val rem: Long)

internal fun restorativeSeconds(
    samples: List<SecondRecord>,
    start: Long,
    end: Long,
    restingHr: Int?,
): Double? {
    val stages = estimateSleepStages(samples, start, end, restingHr)
    val counted = stageSeconds(samples.filter { it.unixSeconds in start..end }, stages) ?: return null
    return (counted.deep + counted.rem).toDouble()
}

private fun stageSeconds(window: List<SecondRecord>, stages: StageEstimate): StageSeconds? {
    if (!stages.available) return null
    val keys = window.map { it.unixSeconds / 60L }.distinct().sorted()
    if (keys.size != stages.minutes.size) return null
    val byMinute = keys.zip(stages.minutes).toMap()
    var awake = 0L
    var light = 0L
    var deep = 0L
    var rem = 0L
    for (sample in window) {
        when (byMinute[sample.unixSeconds / 60L]) {
            SleepStage.AWAKE -> awake += 1
            SleepStage.LIGHT -> light += 1
            SleepStage.DEEP -> deep += 1
            SleepStage.REM -> rem += 1
            null -> Unit
        }
    }
    return StageSeconds(awake, light, deep, rem)
}

private fun slice(seconds: Long, total: Long, typical: Int?): StageSlice = StageSlice(
    seconds = seconds,
    percent = (seconds * 100.0 / total).roundToInt().coerceIn(0, 100),
    typicalPercent = typical,
)

private data class TypicalStages(val awake: Int, val light: Int, val deep: Int, val rem: Int)

private fun typicalStagePercents(
    history: List<DayResult>,
    wakeDate: LocalDate,
    extraNights: Map<LocalDate, List<SecondRecord>>,
): TypicalStages? {
    val percents = ArrayList<StageSeconds>()
    for (day in history) {
        if (!day.date.isBefore(wakeDate)) continue
        val samples = extraNights[day.date] ?: continue
        val main = day.main ?: continue
        val estimate = estimateSleepStages(samples, main.startUnix, main.endUnix, day.rhythm?.restingHr)
        stageSeconds(samples.filter { it.unixSeconds in main.startUnix..main.endUnix }, estimate)?.let { percents += it }
    }
    if (percents.isEmpty()) return null
    fun mean(pick: (StageSeconds) -> Long): Int {
        val ratios = percents.map { row ->
            val total = (row.awake + row.light + row.deep + row.rem).coerceAtLeast(1L)
            (pick(row) * 100.0 / total).roundToInt()
        }
        return ratios.average().roundToInt()
    }
    return TypicalStages(mean { it.awake }, mean { it.light }, mean { it.deep }, mean { it.rem })
}

internal fun needFor(history: List<DayResult>, date: LocalDate): app.pulse.metrics.SleepNeed {
    val prior = history.filter { it.date.isBefore(date) }.sortedBy { it.date }
    val sleeps = prior.mapNotNull { it.main?.sleepSeconds }
    val strain = prior.lastOrNull()?.strain?.trimp?.let { approximateStrain21(it) }
    val baseline = app.pulse.metrics.personalSleepBaselineSeconds(sleeps)
    val shortfalls = sleeps.takeLast(4).map { (baseline - it).coerceAtLeast(0) }
    return sleepNeed(sleeps, strain, shortfalls)
}

private fun consistencyView(history: List<DayResult>, wakeDate: LocalDate, zone: ZoneId): ConsistencyView {
    val nights = lastNights(history, wakeDate, 5)
    val beds = nights.map { minutesFromNoon(it.second.startUnix, zone) }
    val wakes = nights.map { minutesFromNoon(it.second.endUnix, zone) }
    val percent = sleepConsistencyPercent(beds, wakes)
    val earlier = history.filter { it.date.isBefore(wakeDate.minusDays(4)) }
    val priorScores = earlier.mapNotNull { day ->
        val sample = lastNights(history, day.date, 5)
        sleepConsistencyPercent(
            sample.map { minutesFromNoon(it.second.startUnix, zone) },
            sample.map { minutesFromNoon(it.second.endUnix, zone) },
        )
    }
    return ConsistencyView(
        percent = percent,
        priorPercent = averageOfInt(priorScores)?.roundToInt(),
        days = nights.map { (date, episode) ->
            ConsistencyDay(
                label = date.format(WEEKDAY),
                bedMinute = minutesFromNoon(episode.startUnix, zone),
                wakeMinute = minutesFromNoon(episode.endUnix, zone),
                selected = date == wakeDate,
            )
        },
        optimalBed = if (beds.size >= 3) medianOf(beds) else null,
        optimalWake = if (wakes.size >= 3) medianOf(wakes) else null,
    )
}

private fun lastNights(history: List<DayResult>, end: LocalDate, count: Int): List<Pair<LocalDate, SleepEpisode>> =
    history
        .filter { !it.date.isAfter(end) }
        .sortedBy { it.date }
        .mapNotNull { day -> day.main?.let { day.date to it } }
        .takeLast(count)

internal fun minutesFromNoon(unix: Long, zone: ZoneId): Int {
    val time = Instant.ofEpochSecond(unix).atZone(zone)
    val noon = time.toLocalDate().atTime(12, 0).atZone(zone)
    val base = if (time.isBefore(noon)) noon.minusDays(1) else noon
    return ((time.toEpochSecond() - base.toEpochSecond()) / 60L).toInt()
}

private fun weekPoints(
    history: List<DayResult>,
    wakeDate: LocalDate,
    zone: ZoneId,
    openSamples: List<SecondRecord>,
    extraNights: Map<LocalDate, List<SecondRecord>>,
    openResting: Int?,
): List<SleepWeekPoint> {
    val byDate = history.associateBy { it.date }
    return (0 until 7).map { offset ->
        val date = wakeDate.minusDays((6 - offset).toLong())
        val day = byDate[date]
        val main = day?.main
        val need = needFor(history, date)
        val slept = main?.sleepSeconds
        val performance = slept?.let { sleepNeedPercent(it, need.totalSeconds)?.toDouble() }
        val efficiency = if (main != null) sleepEfficiencyPercent(main.sleepSeconds, main.spanSeconds)?.toDouble() else null
        val nights = lastNights(history, date, 5)
        val consistency = sleepConsistencyPercent(
            nights.map { minutesFromNoon(it.second.startUnix, zone) },
            nights.map { minutesFromNoon(it.second.endUnix, zone) },
        )?.toDouble()
        val samples = when {
            date == wakeDate -> openSamples
            else -> extraNights[date]
        }
        val stage = if (samples != null && main != null) {
            val resting = day?.rhythm?.restingHr ?: openResting
            stageSeconds(
                samples.filter { it.unixSeconds in main.startUnix..main.endUnix },
                estimateSleepStages(samples, main.startUnix, main.endUnix, resting),
            )
        } else {
            null
        }
        val debt = slept?.let { (need.totalSeconds - it).coerceAtLeast(0) / 3600.0 }
        SleepWeekPoint(
            date = date,
            label = date.format(WEEKDAY) + "\n" + date.dayOfMonth,
            selected = date == wakeDate,
            performance = performance,
            asleepHours = slept?.div(3600.0),
            neededHours = need?.totalSeconds?.div(3600.0),
            vsNeededPercent = performance,
            deepHours = stage?.deep?.div(3600.0),
            remHours = stage?.rem?.div(3600.0),
            consistency = consistency,
            bedMinute = main?.let { minutesFromNoon(it.startUnix, zone).toDouble() },
            wakeMinute = main?.let { minutesFromNoon(it.endUnix, zone).toDouble() },
            efficiency = efficiency,
            debtHours = debt,
        )
    }
}

private fun downsampleInts(values: List<Int>, buckets: Int): List<Int> {
    if (values.isEmpty()) return emptyList()
    if (values.size <= buckets) return values
    val size = values.size
    return List(buckets) { index ->
        val from = index * size / buckets
        val to = ((index + 1) * size / buckets).coerceAtLeast(from + 1)
        values.subList(from, to).average().roundToInt()
    }
}

private fun downsampleFloats(values: List<Float>, buckets: Int): List<Float> {
    if (values.isEmpty()) return emptyList()
    if (values.size <= buckets) return values
    val size = values.size
    return List(buckets) { index ->
        val from = index * size / buckets
        val to = ((index + 1) * size / buckets).coerceAtLeast(from + 1)
        values.subList(from, to).average().toFloat()
    }
}

private fun downsampleFlags(values: List<Boolean>, buckets: Int): List<Boolean> {
    if (values.isEmpty()) return emptyList()
    if (values.size <= buckets) return values
    val size = values.size
    return List(buckets) { index ->
        val from = index * size / buckets
        val to = ((index + 1) * size / buckets).coerceAtLeast(from + 1)
        values.subList(from, to).count { it } * 2 >= (to - from)
    }
}

private fun medianOf(values: List<Int>): Int {
    val sorted = values.sorted()
    return sorted[sorted.size / 2]
}

private fun averageOfLong(values: List<Long>): Double? = if (values.isEmpty()) null else values.average()

private fun averageOfInt(values: List<Int>): Double? = if (values.isEmpty()) null else values.map { it.toDouble() }.average()

private fun zoneOf(id: String): ZoneId = try {
    ZoneId.of(id)
} catch (_: Exception) {
    ZoneId.systemDefault()
}

private val DATE_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.US)
