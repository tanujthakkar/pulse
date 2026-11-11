package app.pulse.feature.home

import app.pulse.link.LinkPhase
import app.pulse.metrics.ActivityCard
import app.pulse.metrics.ActivityKind
import app.pulse.metrics.DayResult
import app.pulse.metrics.DayRules
import app.pulse.metrics.LoggedActivity
import app.pulse.metrics.activityTimeline
import app.pulse.metrics.approximateStrain21
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * One summary ring.
 *
 * Sleep is a percent of the 8-hour reference in [DayRules.SLEEP_REFERENCE_SECONDS].
 * That reference is not a personal sleep need. Recovery is a percent. Strain is
 * the approximate 0–21 scale.
 */
data class SummaryRingModel(
    val title: String,
    val value: String,
    val progress: Float,
    val tone: DialTone,
    val gradient: Boolean,
)

data class HomeLive(
    val bpm: Int?,
    val phase: LinkPhase = LinkPhase.IDLE,
    val batteryPercent: Int?,
    val lastSyncUnixSeconds: Long?,
    val zone: ZoneId,
    val sampleBanner: String? = null,
    val trace: List<HeartPoint> = emptyList(),
)

data class HeartPoint(
    val tMillis: Long,
    val bpm: Int,
)

data class HomeActivity(
    val card: ActivityCard,
    val tile: String,
    val tileTone: DialTone,
    val startLabel: String,
    val endLabel: String,
    val dayPrefix: String?,
)

data class DashboardCard(
    val name: String,
    val value: String,
    val comparison: String?,
    val trend: Trend?,
    val tag: String,
    val note: String? = null,
)

enum class Trend { UP, DOWN }

data class MetricValue(
    val text: String,
    val available: Boolean,
    val unit: String? = null,
) {
    companion object {
        val EMPTY = MetricValue("--", false)
    }
}

data class HealthMonitorModel(
    val bpmText: String,
    val status: String,
    val trace: List<HeartPoint>,
    val respiratory: MetricValue,
    val spo2: MetricValue,
    val rhr: MetricValue,
    val hrv: MetricValue,
    val skin: MetricValue,
)

data class HomeContent(
    val date: LocalDate,
    val headerLabel: String,
    val isToday: Boolean,
    val zone: ZoneId,
    val sleep: SummaryRingModel,
    val recovery: SummaryRingModel,
    val strain: SummaryRingModel,
    val sleepStartUnix: Long?,
    val activities: List<HomeActivity>,
    val dashboard: List<DashboardCard>,
    val monitor: HealthMonitorModel,
    val sampleBanner: String?,
)

/**
 * Home copy for one local day.
 *
 * A date's numbers come from the day already loaded. Month browsing does not
 * call this. Recovery color uses the rounded percent: below 30 red, from 30
 * below 75 yellow, and 75 and above green.
 */
fun homeContent(
    day: DayResult?,
    date: LocalDate,
    today: LocalDate,
    logged: List<LoggedActivity>,
    live: HomeLive,
    history: List<DayResult> = emptyList(),
    strains: Map<Long, String> = emptyMap(),
): HomeContent {
    val noSeconds = day == null || (day.strapSeconds == 0 && day.edgeSeconds == 0)
    val prior = history.filter { it.date.isBefore(date) && !it.date.isBefore(date.minusDays(30)) }
    return HomeContent(
        date = date,
        headerLabel = DATE.format(date).uppercase(Locale.US),
        isToday = date == today,
        zone = live.zone,
        sleep = sleepRing(day, noSeconds),
        recovery = recoveryRing(day, noSeconds),
        strain = strainRing(day, noSeconds),
        sleepStartUnix = if (noSeconds) null else day?.main?.startUnix,
        activities = activitiesFor(day, date, logged, live.zone, strains),
        dashboard = dashboard(day, noSeconds, prior),
        monitor = healthMonitor(day, noSeconds, prior, live),
        sampleBanner = live.sampleBanner,
    )
}

/**
 * Recovery ring color.
 *
 * Below 30 is red. From 30 up to but not including 75 is yellow. 75 and above
 * is green. The shared endpoints belong to the higher band: 30 is yellow and
 * 75 is green.
 */
enum class RecoveryRingBand { RED, YELLOW, GREEN }

fun recoveryRingBand(percent: Double): RecoveryRingBand = when {
    percent < 30.0 -> RecoveryRingBand.RED
    percent < 75.0 -> RecoveryRingBand.YELLOW
    else -> RecoveryRingBand.GREEN
}

fun recoveryRingTone(percent: Double): DialTone = when (recoveryRingBand(percent)) {
    RecoveryRingBand.RED -> DialTone.RED
    RecoveryRingBand.YELLOW -> DialTone.YELLOW
    RecoveryRingBand.GREEN -> DialTone.GREEN
}

/**
 * Digits and color for a recovery score.
 *
 * The home screen rounds first, then colors that integer, so a score of 74.6
 * is shown as 75% and green together.
 */
fun recoveryRingDisplay(score: Double): Pair<String, DialTone> {
    val shown = score.roundToInt().coerceIn(0, 100)
    return "$shown%" to recoveryRingTone(shown.toDouble())
}

/**
 * Sleep performance against the 8-hour reference.
 *
 * [DayRules.SLEEP_REFERENCE_SECONDS] is not a personal sleep need. A night
 * longer than the reference is shown as 100%.
 */
fun sleepPerformancePercent(sleepSeconds: Int): Int {
    if (sleepSeconds <= 0) return 0
    val raw = sleepSeconds * 100.0 / DayRules.SLEEP_REFERENCE_SECONDS
    return raw.roundToInt().coerceIn(0, 100)
}

/**
 * Weekday prefix when [startUnix] falls on the local day before [selected].
 *
 * The text is that start date's short weekday, such as "[Fri]". The same day
 * and any earlier day return null.
 */
fun previousDayPrefix(startUnix: Long, selected: LocalDate, zone: ZoneId): String? {
    val startDate = Instant.ofEpochSecond(startUnix).atZone(zone).toLocalDate()
    if (startDate != selected.minusDays(1)) return null
    val name = startDate.format(DateTimeFormatter.ofPattern("EEE", Locale.US))
    return "[$name]"
}

fun deviceStatus(phase: LinkPhase): String = when (phase) {
    LinkPhase.LINKED -> "Device connected"
    LinkPhase.SCANNING, LinkPhase.CONNECTING, LinkPhase.BONDING -> "Connecting"
    else -> "Device disconnected"
}

/**
 * Month browsing does not load a day. Only [pick] does.
 */
class HomeDateSelection(
    startMonth: YearMonth,
    private val load: (LocalDate) -> Unit,
) {
    var visibleMonth: YearMonth = startMonth
        private set
    var selected: LocalDate? = null
        private set
    var loads: Int = 0
        private set

    fun browse(month: YearMonth) {
        visibleMonth = month
    }

    fun pick(date: LocalDate) {
        selected = date
        visibleMonth = YearMonth.from(date)
        loads += 1
        load(date)
    }
}

internal fun activitiesFor(
    day: DayResult?,
    date: LocalDate,
    logged: List<LoggedActivity>,
    zone: ZoneId,
    strains: Map<Long, String> = emptyMap(),
): List<HomeActivity> {
    val sleep = day?.episodes.orEmpty().filter { it.endDate == date }
    val manual = logged.filter { Instant.ofEpochSecond(it.startUnix).atZone(zone).toLocalDate() == date }
    return activityTimeline(sleep, manual).map { card ->
        val episode = sleep.firstOrNull { it.startUnix == card.startUnix }
        val tile = if (card.kind == ActivityKind.SLEEP) {
            formatHm((episode?.sleepSeconds ?: (card.endUnix - card.startUnix)).toLong())
        } else {
            strains[card.startUnix] ?: "--"
        }
        HomeActivity(
            card = card,
            tile = tile,
            tileTone = if (card.kind == ActivityKind.SLEEP) DialTone.PURPLE else DialTone.BLUE,
            startLabel = clock(card.startUnix, zone),
            endLabel = clock(card.endUnix, zone),
            dayPrefix = previousDayPrefix(card.startUnix, date, zone),
        )
    }
}

private fun recoveryRing(day: DayResult?, noSeconds: Boolean): SummaryRingModel {
    val score = day?.recovery?.score
    if (noSeconds || day?.main == null || score == null) return blankRing("Recovery")
    val (value, tone) = recoveryRingDisplay(score)
    return SummaryRingModel(
        title = "Recovery",
        value = value,
        progress = (score / 100.0).toFloat().coerceIn(0f, 1f),
        tone = tone,
        gradient = false,
    )
}

private fun strainRing(day: DayResult?, noSeconds: Boolean): SummaryRingModel {
    val trimp = day?.strain?.trimp
    if (noSeconds || trimp == null) return blankRing("Strain")
    val dial = approximateStrain21(trimp)
    return SummaryRingModel(
        title = "Strain",
        value = String.format(Locale.US, "%.1f", dial),
        progress = (dial / 21.0).toFloat().coerceIn(0f, 1f),
        tone = DialTone.BLUE,
        gradient = true,
    )
}

private fun sleepRing(day: DayResult?, noSeconds: Boolean): SummaryRingModel {
    val seconds = day?.main?.sleepSeconds
    if (noSeconds || seconds == null) return blankRing("Sleep")
    val percent = sleepPerformancePercent(seconds)
    val fraction = seconds.toFloat() / DayRules.SLEEP_REFERENCE_SECONDS.toFloat()
    return SummaryRingModel(
        title = "Sleep",
        value = "$percent%",
        progress = fraction.coerceIn(0f, 1f),
        tone = DialTone.PURPLE,
        gradient = true,
    )
}

private fun blankRing(title: String) = SummaryRingModel(
    title = title,
    value = "--",
    progress = 0f,
    tone = DialTone.MUTED,
    gradient = false,
)

/**
 * Cards that can be computed from stored history.
 *
 * Calories are left out. Layout 18 has no energy field, and a calorie
 * estimate would need body mass, which Pulse does not store. Edge workout
 * calories are not used as a Pulse total.
 */
private fun dashboard(day: DayResult?, noSeconds: Boolean, prior: List<DayResult>): List<DashboardCard> {
    if (day == null || noSeconds) return emptyList()
    val cards = ArrayList<DashboardCard>()
    val rmssd = day.rhythm?.rmssdMs
    if (day.main != null && rmssd != null) {
        val shown = rmssd.roundToInt()
        val baseline = prior.mapNotNull { it.rhythm?.rmssdMs }.averageInt()
        val value = shown.toString()
        val comparison = baseline?.toString()
        cards += DashboardCard(
            name = "HEART RATE VARIABILITY",
            value = value,
            comparison = comparison,
            trend = shownTrend(shown.toLong(), baseline?.toLong(), value, comparison),
            tag = "dash_hrv",
        )
    }
    val resting = day.rhythm?.restingHr
    if (day.main != null && resting != null) {
        val baseline = prior.mapNotNull { it.rhythm?.restingHr?.toDouble() }.averageInt()
        val value = resting.toString()
        val comparison = baseline?.toString()
        cards += DashboardCard(
            name = "RESTING HEART RATE",
            value = value,
            comparison = comparison,
            trend = shownTrend(resting.toLong(), baseline?.toLong(), value, comparison),
            tag = "dash_rhr",
        )
    }
    val mean = day.meanHeartBpm
    if (mean != null) {
        val shown = mean.roundToInt()
        val baseline = prior.mapNotNull { it.meanHeartBpm }.averageInt()
        val value = shown.toString()
        val comparison = baseline?.toString()
        cards += DashboardCard(
            name = "AVERAGE HEART RATE",
            value = value,
            comparison = comparison,
            trend = shownTrend(shown.toLong(), baseline?.toLong(), value, comparison),
            tag = "dash_avg_hr",
        )
    }
    val steps = day.stepIncrease + day.edgeStepIncrease
    val stepBaseline = prior
        .filter { it.strapSeconds + it.edgeSeconds > 0 }
        .map { it.stepIncrease + it.edgeStepIncrease }
        .averageLong()
    val stepText = formatSteps(steps)
    val stepCompare = stepBaseline?.let { formatSteps(it) }
    cards += DashboardCard(
        name = "STEPS",
        value = stepText,
        comparison = stepCompare,
        trend = shownTrend(steps, stepBaseline, stepText, stepCompare),
        tag = "dash_steps",
    )
    val sleepSeconds = day.main?.sleepSeconds
    if (sleepSeconds != null) {
        val debt = DayRules.SLEEP_REFERENCE_SECONDS - sleepSeconds
        val baselineDebt = prior.mapNotNull { item ->
            item.main?.sleepSeconds?.let { (DayRules.SLEEP_REFERENCE_SECONDS - it).toDouble() }
        }.averageInt()
        val value = formatSignedHm(debt)
        val comparison = baselineDebt?.let { formatSignedHm(it) }
        cards += DashboardCard(
            name = "SLEEP DEBT",
            value = value,
            comparison = comparison,
            trend = shownTrend(debt.toLong(), baselineDebt?.toLong(), value, comparison),
            tag = "dash_sleep_debt",
            note = "8h reference",
        )
    }
    return cards
}

private fun healthMonitor(
    day: DayResult?,
    noSeconds: Boolean,
    prior: List<DayResult>,
    live: HomeLive,
): HealthMonitorModel {
    val rhythm = if (noSeconds) null else day?.rhythm
    val hrv = rhythm?.rmssdMs?.let { MetricValue(it.roundToInt().toString(), true, "ms") }
        ?: MetricValue("--", false, "ms")
    val rhr = rhythm?.restingHr?.let { MetricValue(it.toString(), true, "bpm") }
        ?: MetricValue("--", false, "bpm")
    val breaths = day?.breathsPerMinute?.let {
        MetricValue(String.format(Locale.US, "%.1f", it), true, "br/min")
    } ?: MetricValue("--", false, "br/min")
    val skin = skinDelta(day, prior)
    return HealthMonitorModel(
        bpmText = live.bpm?.toString() ?: "--",
        status = deviceStatus(live.phase),
        trace = if (live.bpm == null) emptyList() else live.trace,
        respiratory = if (noSeconds) MetricValue("--", false, "br/min") else breaths,
        spo2 = MetricValue("--", false, "%"),
        rhr = if (day?.main == null) MetricValue("--", false, "bpm") else rhr,
        hrv = if (day?.main == null) MetricValue("--", false, "ms") else hrv,
        skin = skin,
    )
}

/**
 * Skin temperature versus the prior nights in the loaded window.
 *
 * Layout 18 stores a Celsius value, shown as °C. A percent saturation is not
 * in that record, so SpO2 stays empty. One night with no earlier skin
 * temperature has no baseline and stays empty too.
 */
private fun skinDelta(day: DayResult?, prior: List<DayResult>): MetricValue {
    val tonight = day?.sleepSkinTempC ?: return MetricValue("--", false, "°C")
    val baseline = prior.mapNotNull { it.sleepSkinTempC }
    if (baseline.isEmpty()) return MetricValue("--", false, "°C")
    return MetricValue(formatTempDelta(tonight - baseline.average()), true, "°C")
}

internal fun formatTempDelta(delta: Double): String {
    val tenths = (delta * 10.0).roundToInt()
    val sign = when {
        tenths > 0 -> "+"
        tenths < 0 -> "-"
        else -> ""
    }
    return String.format(Locale.US, "%s%.1f", sign, abs(tenths) / 10.0)
}

internal fun formatDuration(seconds: Long): String {
    val whole = seconds.coerceAtLeast(0L)
    val hours = whole / 3600
    val minutes = (whole % 3600) / 60
    return "${hours}h ${minutes}m"
}

internal fun formatHm(seconds: Long): String {
    val whole = seconds.coerceAtLeast(0L)
    val hours = whole / 3600
    val minutes = (whole % 3600) / 60
    return "$hours:${minutes.toString().padStart(2, '0')}"
}

internal fun formatSignedHm(seconds: Int): String {
    val sign = if (seconds < 0) "-" else ""
    val whole = abs(seconds)
    val hours = whole / 3600
    val minutes = (whole % 3600) / 60
    return "$sign$hours:${minutes.toString().padStart(2, '0')}"
}

private fun formatSteps(steps: Long): String = String.format(Locale.US, "%,d", steps)

private fun clock(unixSeconds: Long, zone: ZoneId): String =
    CLOCK.format(Instant.ofEpochSecond(unixSeconds).atZone(zone))

private fun shownTrend(current: Long, baseline: Long?, currentText: String, baselineText: String?): Trend? {
    if (baselineText == null || baselineText == currentText) return null
    return trend(current, baseline)
}

private fun trend(current: Long, baseline: Long?): Trend? = when {
    baseline == null || current == baseline -> null
    current > baseline -> Trend.UP
    else -> Trend.DOWN
}

private fun List<Double>.averageInt(): Int? = if (isEmpty()) null else average().roundToInt()

private fun List<Long>.averageLong(): Long? = if (isEmpty()) null else average().roundToLong()

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
