package app.pulse.metrics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * One stored layout-18 second.
 *
 * [heartRateBpm] is already limited to the display window. [rrMillis] are
 * positive intervals only. [activityClass] is 0 still, 1 walk, 2 run, or null.
 * [bandMotion] is 0 WAKE, 1 STILL, 2 SLEEP, 3 UP. [stepCounter] is the
 * cumulative hardware counter, not a daily total. [source] is [SOURCE_STRAP]
 * or [SOURCE_EDGE]. [stepsKnown] is false when that second has no counter.
 * [edgeWindowSleep] marks a second labeled from an Edge onset/offset because
 * band motion was absent. It is not a band-motion SLEEP bit.
 */
data class SecondRecord(
    val unixSeconds: Long,
    val recordIndex: Long,
    val heartRateBpm: Int?,
    val rrMillis: List<Int>,
    val activityClass: Int?,
    val bandMotion: Int?,
    val stepCounter: Int,
    val source: String = SOURCE_STRAP,
    val stepsKnown: Boolean = true,
    val edgeWindowSleep: Boolean = false,
    /** Layout-18 skin temperature in °C when the decoded value is inside 5..45. */
    val skinTempC: Float? = null,
)

const val SOURCE_STRAP: String = "strap"
const val SOURCE_EDGE: String = "edge_import"

/** Published-algorithm constants. The formulas are approximate where noted. */
object DayRules {
    const val SLEEP: Int = 2
    const val BRIDGE_SECONDS: Long = 300
    const val MIN_SLEEP_SECONDS: Int = 20 * 60
    const val RR_MIN_MS: Int = 300
    const val RR_MAX_MS: Int = 2_000
    const val MIN_PAIRS: Int = 200
    const val MIN_BASELINE_DAYS: Int = 3
    const val BASELINE_DAYS: Int = 7
    const val ASSUMED_HR_MAX: Int = 190
    const val STEP_MODULUS: Int = 65_536
    const val STEP_WRAP_MAX: Int = 5_000
    const val GAP_SECONDS: Long = 60

    /**
     * Edge seconds this close to a strap second are the same moment with a
     * different timestamp alignment, not a gap the strap failed to cover.
     */
    const val STRAP_ALIGN_SECONDS: Long = 1

    /**
     * Banister TRIMP that maps to about 13 on the approximate 0–21 dial.
     * It is a display scale, not a measured maximum.
     */
    const val STRAIN_DIAL_TRIMP: Double = 180.0

    /** Reference used only to draw the sleep dial. Not a personal sleep need. */
    const val SLEEP_REFERENCE_SECONDS: Int = 8 * 60 * 60

    /** Banister 1991 male coefficients. Sex is not stored, so every second uses these. */
    const val BANISTER_A: Double = 0.64
    const val BANISTER_B: Double = 1.92
}

data class SleepEpisode(
    val startUnix: Long,
    val endUnix: Long,
    val sleepSeconds: Int,
    val spanSeconds: Long,
    val endDate: LocalDate,
    val edgeProvided: Boolean = false,
)

/**
 * One Edge sleep candidate window.
 *
 * [startUnix] is inclusive and [endUnix] is exclusive. The metrics engine
 * uses it only for Edge seconds that have no band-motion value.
 */
data class EdgeSleepWindow(
    val dayId: String,
    val algoVersion: Long,
    val startUnix: Long,
    val endUnix: Long,
)

/**
 * One Edge day_result row shown beside Pulse metrics.
 *
 * These numbers are not inputs to Pulse recovery or strain.
 */
data class EdgeDayNote(
    val dayId: String,
    val algoVersion: Long,
    val restingHr: Double?,
    val rmssd: Double?,
    val readiness: Double?,
)

data class Rhythm(
    val intervals: Int,
    val accepted: Int,
    val pairs: Int,
    val rmssdMs: Double?,
    val heartSamples: Int,
    val restingHr: Int?,
    val meanHr: Double?,
)

data class DayResult(
    val date: LocalDate,
    val daySeconds: Long,
    val storedSeconds: Int,
    val heartSeconds: Int,
    val anyRrSeconds: Int,
    val acceptedRrSeconds: Int,
    val spanStart: Long?,
    val spanEnd: Long?,
    val missingInsideSpan: Long,
    val gapsAtLeastOneMinute: Int,
    val largestGapSeconds: Long,
    val sleepSeconds: Int,
    val shortEpisodes: Int,
    val shortSleepSeconds: Int,
    val episodes: List<SleepEpisode>,
    val main: SleepEpisode?,
    val rhythm: Rhythm?,
    val recovery: Recovery?,
    val strain: Strain?,
    val stillSeconds: Int,
    val walkSeconds: Int,
    val runSeconds: Int,
    val otherActivitySeconds: Int,
    val stepIncrease: Long,
    val edgeStepIncrease: Long,
    val stepWraps: Int,
    val stepResets: Int,
    val stepsAcrossLongGaps: Long,
    val strapSeconds: Int,
    val edgeSeconds: Int,
    val strapHeartSeconds: Int,
    val edgeHeartSeconds: Int,
    val strapAcceptedRrSeconds: Int,
    val edgeAcceptedRrSeconds: Int,
    val sleepDetection: String,
    val edgeNote: EdgeDayNote?,
    /** Mean of this calendar day's heart-rate samples. Not the sleep-only mean. */
    val meanHeartBpm: Double? = null,
    /**
     * Breaths per minute from respiratory sinus arrhythmia on the main
     * night's RR intervals, or null when that series cannot support a rate.
     */
    val breathsPerMinute: Double? = null,
    /** Mean layout-18 skin temperature during the main night, when enough samples exist. */
    val sleepSkinTempC: Double? = null,
)

data class Recovery(
    val score: Double?,
    val baselineDays: Int,
    val baselineRmssd: Double?,
    val baselineRestingHr: Double?,
    val lnRatio: Double?,
    val restingDelta: Double?,
    val unclamped: Double?,
)

data class Strain(
    val trimp: Double?,
    val seconds: Int,
    val restingHr: Int?,
    val below100: Int,
    val from100: Int,
    val from120: Int,
    val from140: Int,
    val from160: Int,
)

data class DayBook(
    val zone: String,
    val days: List<DayResult>,
    val emptyReason: String?,
)

/**
 * Per-day sleep, recovery, strain, and activity from stored seconds.
 *
 * Nothing here is a WHOOP score. Sleep is band-motion SLEEP, not light,
 * deep, or REM. An Edge onset/offset is used only where band motion is
 * absent, and it is labeled Edge-provided. Recovery and strain are Pulse
 * formulas. Edge day_result values are displayed and are not those formulas.
 */
fun computeDays(
    records: List<SecondRecord>,
    zone: ZoneId,
    edgeWindows: List<EdgeSleepWindow> = emptyList(),
    edgeScores: List<EdgeDayNote> = emptyList(),
): DayBook {
    val samples = labelEdgeWindows(dedupe(dropEdgeBesideStrap(records)), edgeWindows)
    val scoreDates = edgeScores.mapNotNull { scoreDate(it.dayId) }
    if (samples.isEmpty() && scoreDates.isEmpty()) {
        return DayBook(zone.id, emptyList(), "No layout-18 or Edge one-second rows are stored.")
    }
    val episodes = sleepEpisodes(samples, zone)
    val steps = stepDeltas(samples, zone)
    val dates = LinkedHashSet<LocalDate>()
    for (sample in samples) dates += localDate(sample.unixSeconds, zone)
    for (episode in episodes) dates += episode.endDate
    dates.addAll(scoreDates)
    val days = dates.map { date ->
        dayResult(date, zone, samples, episodes, steps, edgeScores)
    }.sortedByDescending { it.date }
    return DayBook(zone.id, days, null)
}

fun dayBookText(book: DayBook): String = buildString {
    append(METHOD)
    append("\nTime zone: ")
    append(book.zone)
    append('\n')
    if (book.emptyReason != null) {
        append(book.emptyReason)
        append('\n')
    }
    if (book.days.isEmpty()) return@buildString
    for (day in book.days) {
        append('\n')
        append(dayText(day, book.zone))
    }
}

private const val METHOD: String =
    "Approximate day metrics from stored layout-18 seconds and Edge import seconds. Not WHOOP scores.\n" +
        "A strap second wins over an Edge second at the same Unix time. " +
        "An Edge second within 1 s of a strap second is dropped so a shifted timestamp " +
        "is not counted twice. Edge rows fill only seconds the strap does not cover. " +
        "Step increases stay inside one source and do not jump across the other source.\n" +
        "Sleep uses band-motion SLEEP when that value is stored (strap frame byte 81 bits 4-5, " +
        "or Edge band_sleep_state 0 WAKE, 1 STILL, 2 SLEEP, 3 UP). It is not light, deep, or REM. " +
        "Where Edge seconds have no band-motion value, an Edge onset/offset labels those seconds. " +
        "That window is Edge-provided. " +
        "SLEEP seconds separated by under 5 minutes stay one episode. " +
        "An episode under 20 minutes is short and is not the main sleep.\n" +
        "Edge day_result resting HR, RMSSD, and readiness are printed as Edge. " +
        "They are not Pulse recovery or strain.\n" +
        "RMSSD is the Task Force (1996) root mean square of successive differences. " +
        "Intervals outside 300-2000 ms are dropped. A successive change above 20% of the previous " +
        "interval is dropped. Those two filters are approximate.\n" +
        "Resting HR is the 10th percentile of heart rate during the main sleep. Approximate.\n" +
        "Recovery is 50 + 40*ln(today RMSSD / baseline RMSSD) - 2.5*(today RHR - baseline RHR), " +
        "clamped to 0-100. The baseline is the median of up to 7 earlier qualifying days. " +
        "A day qualifies with a main sleep and at least 200 accepted pairs. " +
        "Fewer than 3 baseline days leaves the score blank. Approximate. Not a clinical score.\n" +
        "Strain is Banister TRIMP with the 1991 male coefficients, assumed max HR 190, and " +
        "resting HR from that day's main sleep. Sex and max HR are not measured. " +
        "Approximate. Not a 0-21 strain score.\n" +
        "Activity seconds use classes 0 still, 1 walk, and 2 run. " +
        "Steps are the increase in the cumulative hardware counter, including a 16-bit wrap " +
        "under 5000 counts. The counter is documented to over-count versus an official total.\n" +
        "Heart-rate coverage and R-R availability are shares of stored seconds. " +
        "Gaps are missing seconds inside the first-to-last stored second of that local day."

private fun dayText(day: DayResult, zone: String): String = buildString {
    append("Day ")
    append(day.date)
    append('\n')
    append("Source: ")
    append(sourceLabel(day.strapSeconds, day.edgeSeconds))
    append(". Strap seconds ")
    append(day.strapSeconds)
    append(" (HR ")
    append(day.strapHeartSeconds)
    append(" / ")
    append(day.strapSeconds)
    append(", R-R in 300-2000 ms ")
    append(day.strapAcceptedRrSeconds)
    append(" / ")
    append(day.strapSeconds)
    append("). Edge import seconds ")
    append(day.edgeSeconds)
    append(" (HR ")
    append(day.edgeHeartSeconds)
    append(" / ")
    append(day.edgeSeconds)
    append(", R-R in 300-2000 ms ")
    append(day.edgeAcceptedRrSeconds)
    append(" / ")
    append(day.edgeSeconds)
    append(").\n")
    append("Sleep detection: ")
    append(day.sleepDetection)
    append(".\n")
    append("Stored ")
    append(day.storedSeconds)
    append(" of ")
    append(day.daySeconds)
    append(" day seconds (")
    append(pct(day.storedSeconds.toLong(), day.daySeconds))
    append(").\n")
    append("HR coverage ")
    append(day.heartSeconds)
    append(" / ")
    append(day.storedSeconds)
    append(" stored seconds (")
    append(pct(day.heartSeconds, day.storedSeconds))
    append(").\n")
    append("R-R in 300-2000 ms on ")
    append(day.acceptedRrSeconds)
    append(" stored seconds (")
    append(pct(day.acceptedRrSeconds, day.storedSeconds))
    append("). Any positive R-R on ")
    append(day.anyRrSeconds)
    append(".\n")
    if (day.spanStart == null || day.spanEnd == null) {
        append("No stored span.\n")
    } else {
        append("Span ")
        append(clock(day.spanStart, zone))
        append(" to ")
        append(clock(day.spanEnd, zone))
        append(". Missing inside the span: ")
        append(day.missingInsideSpan)
        append(" s. Gaps of at least 60 s: ")
        append(day.gapsAtLeastOneMinute)
        append(". Largest gap: ")
        append(day.largestGapSeconds)
        append(" s.\n")
    }
    append("Band-motion SLEEP seconds on this date: ")
    append(day.sleepSeconds)
    append(".\n")
    append("Short episodes: ")
    append(day.shortEpisodes)
    append(", ")
    append(day.shortSleepSeconds)
    append(" SLEEP seconds.\n")
    if (day.main == null) {
        append("Main sleep: none ending this day.\n")
    } else {
        append(if (day.main.edgeProvided) "Main sleep (Edge-provided onset/offset): " else "Main sleep: ")
        append(episodeText(day.main, zone))
        append('\n')
    }
    for (episode in day.episodes) {
        if (episode == day.main) continue
        append("Other episode: ")
        append(episodeText(episode, zone))
        append('\n')
    }
    val rhythm = day.rhythm
    if (rhythm == null) {
        append("Main-sleep rhythm: none.\n")
    } else {
        append("Main-sleep intervals ")
        append(rhythm.intervals)
        append(", accepted ")
        append(rhythm.accepted)
        append(", pairs ")
        append(rhythm.pairs)
        append(", RMSSD ")
        append(num(rhythm.rmssdMs))
        append(" ms, HR samples ")
        append(rhythm.heartSamples)
        append(", resting HR ")
        append(rhythm.restingHr?.toString() ?: "none")
        append(" bpm, mean HR ")
        append(num(rhythm.meanHr))
        append(" bpm.\n")
    }
    val recovery = day.recovery
    if (recovery == null) {
        append("Recovery: blank. No main sleep.\n")
    } else if (recovery.score == null) {
        append("Recovery: blank. Baseline days ")
        append(recovery.baselineDays)
        append(" of ")
        append(DayRules.MIN_BASELINE_DAYS)
        append(". Baseline RMSSD ")
        append(num(recovery.baselineRmssd))
        append(" ms. Baseline resting HR ")
        append(num(recovery.baselineRestingHr))
        append(" bpm.\n")
    } else {
        append("Recovery: ")
        append(num(recovery.score))
        append(" approximate. Unclamped ")
        append(num(recovery.unclamped))
        append(". Baseline days ")
        append(recovery.baselineDays)
        append(". Baseline RMSSD ")
        append(num(recovery.baselineRmssd))
        append(" ms. Baseline resting HR ")
        append(num(recovery.baselineRestingHr))
        append(" bpm. ln ratio ")
        append(num(recovery.lnRatio))
        append(". Resting HR delta ")
        append(num(recovery.restingDelta))
        append(" bpm.\n")
    }
    val strain = day.strain
    if (strain == null) {
        append("Strain: none.\n")
    } else {
        append("Banister TRIMP: ")
        append(num(strain.trimp))
        append(" (assumed max HR ")
        append(DayRules.ASSUMED_HR_MAX)
        append(", resting HR ")
        append(strain.restingHr?.toString() ?: "none")
        append("). Seconds in the sum: ")
        append(strain.seconds)
        append(".\n")
        append("HR bands: under 100 ")
        append(strain.below100)
        append(" s, 100-119 ")
        append(strain.from100)
        append(" s, 120-139 ")
        append(strain.from120)
        append(" s, 140-159 ")
        append(strain.from140)
        append(" s, 160 and above ")
        append(strain.from160)
        append(" s.\n")
    }
    append("Activity: still ")
    append(day.stillSeconds)
    append(" s, walk ")
    append(day.walkSeconds)
    append(" s, run ")
    append(day.runSeconds)
    append(" s, other ")
    append(day.otherActivitySeconds)
    append(" s.\n")
    val note = day.edgeNote
    if (note != null) {
        append("Edge day_result (not used for Pulse recovery or strain): algo ")
        append(note.algoVersion)
        append(", resting HR ")
        append(num(note.restingHr))
        append(", RMSSD ")
        append(num(note.rmssd))
        append(", readiness ")
        append(num(note.readiness))
        append(".\n")
    }
    append("Step-counter increase ")
    append(day.stepIncrease)
    append(". Edge step_count increase ")
    append(day.edgeStepIncrease)
    append(". Wraps ")
    append(day.stepWraps)
    append(". Resets ")
    append(day.stepResets)
    append(". Increase across gaps over 60 s: ")
    append(day.stepsAcrossLongGaps)
    append(".\n")
}

private fun episodeText(episode: SleepEpisode, zone: String): String {
    val kind = if (episode.edgeProvided) " Edge-provided onset/offset." else ""
    return clock(episode.startUnix, zone) + " to " + clock(episode.endUnix, zone) +
        ", " + episode.sleepSeconds + " SLEEP seconds, span " + episode.spanSeconds + " s." + kind
}

internal fun banisterSecond(heartRate: Int, resting: Int, maxHr: Int): Double {
    if (maxHr <= resting) return 0.0
    val reserve = (heartRate - resting).toDouble() / (maxHr - resting).toDouble()
    val clamped = reserve.coerceIn(0.0, 1.0)
    return clamped * DayRules.BANISTER_A * exp(DayRules.BANISTER_B * clamped) / 60.0
}

private fun dayResult(
    date: LocalDate,
    zone: ZoneId,
    samples: List<SecondRecord>,
    episodes: List<SleepEpisode>,
    steps: List<StepDelta>,
    edgeScores: List<EdgeDayNote>,
): DayResult {
    val start = date.atStartOfDay(zone).toEpochSecond()
    val end = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val daySeconds = end - start
    val mine = samples.filter { it.unixSeconds in start until end }
    val heart = mine.count { it.heartRateBpm != null }
    val anyRr = mine.count { it.rrMillis.isNotEmpty() }
    val acceptedRr = mine.count { sample -> sample.rrMillis.any { it in DayRules.RR_MIN_MS..DayRules.RR_MAX_MS } }
    val span = spanOf(mine)
    val overlapping = episodes.filter { it.endUnix >= start && it.startUnix < end }
    val qualifying = overlapping.filter { it.sleepSeconds >= DayRules.MIN_SLEEP_SECONDS }
    val short = overlapping.filter { it.sleepSeconds < DayRules.MIN_SLEEP_SECONDS }
    val main = qualifying.filter { it.endDate == date }.maxWithOrNull(compareBy({ it.sleepSeconds }, { it.spanSeconds }))
    val rhythm = main?.let { measure(samples, it) }
    val prior = episodes
        .filter { it.endDate < date && it.sleepSeconds >= DayRules.MIN_SLEEP_SECONDS }
        .sortedByDescending { it.endDate }
    val recovery = scoreRecovery(main, rhythm, prior, samples)
    val strain = scoreStrain(mine, rhythm?.restingHr)
    var still = 0
    var walk = 0
    var run = 0
    var other = 0
    for (sample in mine) {
        when (sample.activityClass) {
            0 -> still += 1
            1 -> walk += 1
            2 -> run += 1
            else -> other += 1
        }
    }
    val daySteps = steps.filter { it.date == date }
    val hearts = mine.mapNotNull { it.heartRateBpm }
    val meanHeart = if (hearts.isEmpty()) null else hearts.average()
    val sleepSamples = if (main == null) {
        emptyList()
    } else {
        samples.filter {
            it.unixSeconds in main.startUnix..main.endUnix &&
                (it.bandMotion == DayRules.SLEEP || it.edgeWindowSleep)
        }.sortedBy { it.unixSeconds }
    }
    val breaths = respiratoryRateFromRr(sleepSamples.flatMap { it.rrMillis })
    val temps = sleepSamples.mapNotNull { sample -> sample.skinTempC?.takeIf { it in 5f..45f } }
    val skin = if (temps.size < 30) null else temps.sumOf { it.toDouble() } / temps.size
    return DayResult(
        date = date,
        daySeconds = daySeconds,
        storedSeconds = mine.size,
        heartSeconds = heart,
        anyRrSeconds = anyRr,
        acceptedRrSeconds = acceptedRr,
        spanStart = span?.first,
        spanEnd = span?.second,
        missingInsideSpan = span?.third ?: 0,
        gapsAtLeastOneMinute = span?.fourth ?: 0,
        largestGapSeconds = span?.fifth ?: 0,
        sleepSeconds = mine.count { it.bandMotion == DayRules.SLEEP || it.edgeWindowSleep },
        shortEpisodes = short.size,
        shortSleepSeconds = short.sumOf { it.sleepSeconds },
        episodes = qualifying,
        main = main,
        rhythm = rhythm,
        recovery = if (main == null) null else recovery,
        strain = strain,
        stillSeconds = still,
        walkSeconds = walk,
        runSeconds = run,
        otherActivitySeconds = other,
        stepIncrease = daySteps.filter { it.source == SOURCE_STRAP }.sumOf { it.increase },
        edgeStepIncrease = daySteps.filter { it.source == SOURCE_EDGE }.sumOf { it.increase },
        stepWraps = daySteps.count { it.wrap },
        stepResets = daySteps.count { it.reset },
        stepsAcrossLongGaps = daySteps.filter { it.longGap }.sumOf { it.increase },
        strapSeconds = mine.count { it.source == SOURCE_STRAP },
        edgeSeconds = mine.count { it.source == SOURCE_EDGE },
        strapHeartSeconds = mine.count { it.source == SOURCE_STRAP && it.heartRateBpm != null },
        edgeHeartSeconds = mine.count { it.source == SOURCE_EDGE && it.heartRateBpm != null },
        strapAcceptedRrSeconds = mine.count { sample ->
            sample.source == SOURCE_STRAP && sample.rrMillis.any { it in DayRules.RR_MIN_MS..DayRules.RR_MAX_MS }
        },
        edgeAcceptedRrSeconds = mine.count { sample ->
            sample.source == SOURCE_EDGE && sample.rrMillis.any { it in DayRules.RR_MIN_MS..DayRules.RR_MAX_MS }
        },
        sleepDetection = sleepDetection(mine),
        edgeNote = edgeScores.filter { it.dayId == date.toString() }.maxByOrNull { it.algoVersion },
        meanHeartBpm = meanHeart,
        breathsPerMinute = breaths,
        sleepSkinTempC = skin,
    )
}

private fun sleepDetection(samples: List<SecondRecord>): String {
    val band = samples.any { it.bandMotion == DayRules.SLEEP }
    val window = samples.any { it.edgeWindowSleep }
    return when {
        band && window -> "band-motion and Edge onset/offset"
        window -> "Edge onset/offset"
        band -> "band-motion"
        else -> "none"
    }
}

private fun sourceLabel(strapSeconds: Int, edgeSeconds: Int): String = when {
    strapSeconds > 0 && edgeSeconds > 0 -> "mixed"
    edgeSeconds > 0 -> "Edge import"
    strapSeconds > 0 -> "strap"
    else -> "no data"
}

internal fun dropEdgeBesideStrap(
    records: List<SecondRecord>,
    alignSeconds: Long = DayRules.STRAP_ALIGN_SECONDS,
): List<SecondRecord> {
    val strap = records.asSequence().filter { it.source == SOURCE_STRAP }.map { it.unixSeconds }.toHashSet()
    if (strap.isEmpty()) return records
    val covered = HashSet<Long>(strap.size * 3)
    for (second in strap) {
        var offset = -alignSeconds
        while (offset <= alignSeconds) {
            covered += second + offset
            offset += 1
        }
    }
    return records.filter { it.source != SOURCE_EDGE || it.unixSeconds !in covered }
}

/**
 * Display-only map from Banister TRIMP onto a 0–21 dial.
 *
 * `21 * (1 - exp(-trimp / 180))`. Zero TRIMP is 0. The curve approaches 21
 * and never claims a proprietary strain score. 180 is [DayRules.STRAIN_DIAL_TRIMP].
 */
fun approximateStrain21(trimp: Double): Double {
    if (trimp <= 0.0) return 0.0
    val scaled = 21.0 * (1.0 - exp(-trimp / DayRules.STRAIN_DIAL_TRIMP))
    return scaled.coerceIn(0.0, 21.0)
}

/**
 * Breaths per minute from respiratory sinus arrhythmia on RR intervals.
 *
 * This is not a stored respiratory channel. Local peaks in the RR tachogram
 * that rise at least 8 ms above a nearby mean, and that sit at least 2 seconds
 * apart, are counted as breaths. The rate is `60 * (peaks - 1) / span`.
 * The result is kept only for 8..30 breaths/min when at least 8 peaks span
 * 60 seconds. Anything flatter, shorter, or outside that range is null.
 */
fun respiratoryRateFromRr(intervalsMs: List<Int>): Double? {
    val accepted = intervalsMs.filter { it in DayRules.RR_MIN_MS..DayRules.RR_MAX_MS }
    if (accepted.size < 30) return null
    val time = DoubleArray(accepted.size)
    var cursor = 0.0
    for (index in accepted.indices) {
        cursor += accepted[index] / 1000.0
        time[index] = cursor
    }
    val peaks = ArrayList<Int>()
    for (index in 1 until accepted.lastIndex) {
        val value = accepted[index]
        if (value <= accepted[index - 1] || value < accepted[index + 1]) continue
        var sum = 0.0
        var count = 0
        val from = (index - 8).coerceAtLeast(0)
        val to = (index + 8).coerceAtMost(accepted.lastIndex)
        for (neighbor in from..to) {
            sum += accepted[neighbor]
            count += 1
        }
        if (value - sum / count < 8.0) continue
        val last = peaks.lastOrNull()
        if (last != null && time[index] - time[last] < 2.0) continue
        peaks += index
    }
    if (peaks.size < 8) return null
    val span = time[peaks.last()] - time[peaks.first()]
    if (span < 60.0) return null
    val rate = 60.0 * (peaks.size - 1) / span
    if (rate !in 8.0..30.0) return null
    return rate
}

private fun spanOf(samples: List<SecondRecord>): Span? {
    if (samples.isEmpty()) return null
    val first = samples.first().unixSeconds
    val last = samples.last().unixSeconds
    val observed = last - first + 1
    val missing = observed - samples.size
    var gaps = 0
    var largest = 0L
    for (index in 1 until samples.size) {
        val hole = samples[index].unixSeconds - samples[index - 1].unixSeconds - 1
        if (hole >= DayRules.GAP_SECONDS) gaps += 1
        if (hole > largest) largest = hole
    }
    return Span(first, last, missing, gaps, largest)
}

private data class Span(
    val first: Long,
    val second: Long,
    val third: Long,
    val fourth: Int,
    val fifth: Long,
)

private fun scoreRecovery(
    main: SleepEpisode?,
    rhythm: Rhythm?,
    prior: List<SleepEpisode>,
    samples: List<SecondRecord>,
): Recovery {
    val qualifying = ArrayList<Rhythm>()
    for (episode in prior) {
        if (qualifying.size == DayRules.BASELINE_DAYS) break
        val measured = measure(samples, episode)
        if (measured.pairs >= DayRules.MIN_PAIRS && measured.rmssdMs != null && measured.restingHr != null) {
            qualifying += measured
        }
    }
    val todayReady = main != null && rhythm != null && rhythm.pairs >= DayRules.MIN_PAIRS &&
        rhythm.rmssdMs != null && rhythm.restingHr != null
    if (!todayReady || qualifying.size < DayRules.MIN_BASELINE_DAYS) {
        return Recovery(
            score = null,
            baselineDays = qualifying.size,
            baselineRmssd = median(qualifying.mapNotNull { it.rmssdMs }),
            baselineRestingHr = median(qualifying.mapNotNull { it.restingHr?.toDouble() }),
            lnRatio = null,
            restingDelta = null,
            unclamped = null,
        )
    }
    val today = checkNotNull(rhythm)
    val todayRmssd = checkNotNull(today.rmssdMs)
    val todayResting = checkNotNull(today.restingHr).toDouble()
    val baseRmssd = median(qualifying.map { checkNotNull(it.rmssdMs) })!!
    val baseHr = median(qualifying.map { checkNotNull(it.restingHr).toDouble() })!!
    if (baseRmssd <= 0.0 || todayRmssd <= 0.0) {
        return Recovery(null, qualifying.size, baseRmssd, baseHr, null, null, null)
    }
    val ratio = ln(todayRmssd / baseRmssd)
    val delta = todayResting - baseHr
    val raw = 50.0 + 40.0 * ratio - 2.5 * delta
    return Recovery(
        score = raw.coerceIn(0.0, 100.0),
        baselineDays = qualifying.size,
        baselineRmssd = baseRmssd,
        baselineRestingHr = baseHr,
        lnRatio = ratio,
        restingDelta = delta,
        unclamped = raw,
    )
}

private fun scoreStrain(samples: List<SecondRecord>, resting: Int?): Strain {
    var below = 0
    var from100 = 0
    var from120 = 0
    var from140 = 0
    var from160 = 0
    var seconds = 0
    var sum = 0.0
    for (sample in samples) {
        val hr = sample.heartRateBpm ?: continue
        when {
            hr < 100 -> below += 1
            hr < 120 -> from100 += 1
            hr < 140 -> from120 += 1
            hr < 160 -> from140 += 1
            else -> from160 += 1
        }
        if (resting != null) {
            seconds += 1
            sum += banisterSecond(hr, resting, DayRules.ASSUMED_HR_MAX)
        }
    }
    return Strain(
        trimp = if (resting == null) null else sum,
        seconds = seconds,
        restingHr = resting,
        below100 = below,
        from100 = from100,
        from120 = from120,
        from140 = from140,
        from160 = from160,
    )
}

private fun measure(samples: List<SecondRecord>, episode: SleepEpisode): Rhythm {
    val inside = samples.filter {
        it.unixSeconds in episode.startUnix..episode.endUnix &&
            (it.bandMotion == DayRules.SLEEP || it.edgeWindowSleep)
    }
    val accepted = ArrayList<Int>()
    var intervals = 0
    val hearts = ArrayList<Int>()
    for (sample in inside) {
        sample.heartRateBpm?.let { hearts += it }
        for (interval in sample.rrMillis) {
            intervals += 1
            if (interval in DayRules.RR_MIN_MS..DayRules.RR_MAX_MS) accepted += interval
        }
    }
    var sum = 0.0
    var pairs = 0
    for (index in 1 until accepted.size) {
        val previous = accepted[index - 1]
        val current = accepted[index]
        val diff = kotlin.math.abs(current - previous)
        if (diff * 5 <= previous) {
            sum += diff.toDouble() * diff.toDouble()
            pairs += 1
        }
    }
    return Rhythm(
        intervals = intervals,
        accepted = accepted.size,
        pairs = pairs,
        rmssdMs = if (pairs == 0) null else sqrt(sum / pairs),
        heartSamples = hearts.size,
        restingHr = percentile10(hearts),
        meanHr = if (hearts.isEmpty()) null else hearts.average(),
    )
}

private fun sleepEpisodes(samples: List<SecondRecord>, zone: ZoneId): List<SleepEpisode> {
    val sleep = samples.filter { it.bandMotion == DayRules.SLEEP || it.edgeWindowSleep }
    if (sleep.isEmpty()) return emptyList()
    val runs = ArrayList<MutableList<SecondRecord>>()
    var current = mutableListOf(sleep.first())
    for (index in 1 until sleep.size) {
        val gap = sleep[index].unixSeconds - sleep[index - 1].unixSeconds
        if (gap < DayRules.BRIDGE_SECONDS) {
            current += sleep[index]
        } else {
            runs += current
            current = mutableListOf(sleep[index])
        }
    }
    runs += current
    return runs.map { run ->
        val start = run.first().unixSeconds
        val end = run.last().unixSeconds
        SleepEpisode(
            startUnix = start,
            endUnix = end,
            sleepSeconds = run.size,
            spanSeconds = end - start + 1,
            endDate = localDate(end, zone),
            edgeProvided = run.all { it.edgeWindowSleep && it.bandMotion != DayRules.SLEEP },
        )
    }
}

private data class StepDelta(
    val date: LocalDate,
    val source: String,
    val increase: Long,
    val wrap: Boolean,
    val reset: Boolean,
    val longGap: Boolean,
)

private fun stepDeltas(samples: List<SecondRecord>, zone: ZoneId): List<StepDelta> {
    if (samples.size < 2) return emptyList()
    val deltas = ArrayList<StepDelta>()
    for (index in 1 until samples.size) {
        val previous = samples[index - 1]
        val current = samples[index]
        if (!previous.stepsKnown || !current.stepsKnown || previous.source != current.source) continue
        val gap = current.unixSeconds - previous.unixSeconds
        val raw = current.stepCounter - previous.stepCounter
        val wrapped = raw < 0 &&
            previous.stepCounter in 0 until DayRules.STEP_MODULUS &&
            current.stepCounter in 0 until DayRules.STEP_MODULUS &&
            DayRules.STEP_MODULUS - previous.stepCounter + current.stepCounter <= DayRules.STEP_WRAP_MAX
        val increase = when {
            wrapped -> (DayRules.STEP_MODULUS - previous.stepCounter + current.stepCounter).toLong()
            raw >= 0 -> raw.toLong()
            else -> 0L
        }
        deltas += StepDelta(
            date = localDate(current.unixSeconds, zone),
            source = current.source,
            increase = increase,
            wrap = wrapped,
            reset = raw < 0 && !wrapped,
            longGap = gap > DayRules.GAP_SECONDS,
        )
    }
    return deltas
}

internal fun labelEdgeWindows(
    samples: List<SecondRecord>,
    windows: List<EdgeSleepWindow>,
): List<SecondRecord> {
    if (samples.isEmpty() || windows.isEmpty()) return samples
    val chosen = windows
        .filter { it.endUnix > it.startUnix }
        .groupBy { it.dayId }
        .values
        .map { rows -> rows.maxBy { it.algoVersion } }
    val relabel = HashSet<Int>()
    for (window in chosen) {
        val inside = samples.indices.filter { index ->
            val sample = samples[index]
            sample.source == SOURCE_EDGE &&
                sample.unixSeconds >= window.startUnix &&
                sample.unixSeconds < window.endUnix
        }
        if (inside.isEmpty()) continue
        if (inside.any { samples[it].bandMotion != null }) continue
        relabel.addAll(inside)
    }
    if (relabel.isEmpty()) return samples
    return samples.mapIndexed { index, sample ->
        if (index in relabel) sample.copy(edgeWindowSleep = true) else sample
    }
}

private fun scoreDate(dayId: String): LocalDate? = try {
    LocalDate.parse(dayId)
} catch (_: Exception) {
    null
}

private fun dedupe(records: List<SecondRecord>): List<SecondRecord> {
    val ordered = records.sortedWith(compareBy({ it.unixSeconds }, { it.recordIndex }))
    val kept = ArrayList<SecondRecord>()
    for (record in ordered) {
        val last = kept.lastOrNull()
        if (last != null && last.unixSeconds == record.unixSeconds) {
            kept[kept.lastIndex] = record
        } else {
            kept += record
        }
    }
    return kept
}

private fun percentile10(values: List<Int>): Int? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val rank = ceil(0.10 * sorted.size).toInt().coerceAtLeast(1)
    return sorted[rank - 1]
}

private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

private fun localDate(unixSeconds: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochSecond(unixSeconds).atZone(zone).toLocalDate()

private fun clock(unixSeconds: Long, zone: String): String =
    Instant.ofEpochSecond(unixSeconds).atZone(ZoneId.of(zone)).toLocalTime().toString()

private fun pct(part: Int, whole: Int): String = pct(part.toLong(), whole.toLong())

private fun pct(part: Long, whole: Long): String {
    if (whole <= 0) return "—"
    return String.format(Locale.US, "%.1f%%", 100.0 * part / whole)
}

private fun num(value: Double?): String {
    if (value == null) return "none"
    return String.format(Locale.US, "%.4f", value)
}

/**
 * HRV and resting HR for one sleep window.
 *
 * Uses the same second range as the day's main-sleep rhythm. Stages are not
 * computed. Stored band motion is only wake, still, sleep, or up.
 */
fun sleepRhythm(
    records: List<SecondRecord>,
    startUnix: Long,
    endUnix: Long,
    edgeProvided: Boolean,
): Rhythm {
    val episode = SleepEpisode(
        startUnix = startUnix,
        endUnix = endUnix,
        sleepSeconds = (endUnix - startUnix).coerceAtLeast(0L).toInt(),
        spanSeconds = (endUnix - startUnix).coerceAtLeast(0L),
        endDate = LocalDate.EPOCH,
        edgeProvided = edgeProvided,
    )
    return measure(records, episode)
}
