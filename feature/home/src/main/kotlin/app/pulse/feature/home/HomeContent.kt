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
import kotlin.math.roundToInt

data class DialModel(
    val title: String,
    val value: String,
    val progress: Float,
    val tone: DialTone,
)

data class HomeLive(
    val bpm: Int?,
    val phase: LinkPhase = LinkPhase.IDLE,
    val batteryPercent: Int?,
    val lastSyncUnixSeconds: Long?,
    val zone: ZoneId,
    val sampleBanner: String? = null,
)

data class HomeActivity(
    val card: ActivityCard,
    val timeLabel: String,
)

data class HomeContent(
    val date: LocalDate,
    val headerLabel: String,
    val isToday: Boolean,
    val zone: ZoneId,
    val sourceLabel: String,
    val sleep: DialModel,
    val recovery: DialModel,
    val strain: DialModel,
    val activities: List<HomeActivity>,
    val hrv: String,
    val resting: String,
    val sleepStat: String,
    val steps: String,
    val liveValue: String,
    val lastSync: String,
    val battery: String,
    val sampleBanner: String?,
)

/**
 * Home-screen copy for one local day.
 *
 * The dials are the value only. Recovery under 34 is red, under 67 is yellow,
 * and 67 and above is green. Those cuts are display bands for the Pulse
 * formula. Strain is the approximate 0–21 dial.
 */
fun homeContent(
    day: DayResult?,
    date: LocalDate,
    today: LocalDate,
    logged: List<LoggedActivity>,
    live: HomeLive,
): HomeContent {
    val noSeconds = day == null || (day.strapSeconds == 0 && day.edgeSeconds == 0)
    val rhythm = day?.rhythm
    val rmssd = rhythm?.rmssdMs
    val restingHr = rhythm?.restingHr
    val hrv = when {
        noSeconds || day?.main == null || rmssd == null -> "—"
        else -> "${rmssd.roundToInt()} ms"
    }
    val resting = when {
        noSeconds || day?.main == null || restingHr == null -> "—"
        else -> "$restingHr bpm"
    }
    val sleepSeconds = day?.main?.sleepSeconds
    val sleepStat = if (sleepSeconds == null || noSeconds) "—" else formatDuration(sleepSeconds.toLong())
    val steps = if (noSeconds) "—" else formatSteps((day?.stepIncrease ?: 0L) + (day?.edgeStepIncrease ?: 0L))
    val bpm = live.bpm?.let { "$it bpm" } ?: "—"
    return HomeContent(
        date = date,
        headerLabel = if (date == today) "Today" else DATE.format(date),
        isToday = date == today,
        zone = live.zone,
        sourceLabel = sourceLabel(day, noSeconds),
        sleep = sleepDial(day, noSeconds),
        recovery = recoveryDial(day, noSeconds),
        strain = strainDial(day, noSeconds),
        activities = activitiesFor(day, date, logged, live.zone),
        hrv = hrv,
        resting = resting,
        sleepStat = sleepStat,
        steps = steps,
        liveValue = "$bpm · ${connectionLabel(live.phase)}",
        lastSync = lastSync(live),
        battery = live.batteryPercent?.let { "$it%" } ?: "—",
        sampleBanner = live.sampleBanner,
    )
}

fun connectionLabel(phase: LinkPhase): String = when (phase) {
    LinkPhase.LINKED -> "Connected"
    LinkPhase.SCANNING, LinkPhase.CONNECTING, LinkPhase.BONDING -> "Connecting"
    else -> "Not connected"
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
): List<HomeActivity> {
    val sleep = day?.episodes.orEmpty().filter { it.endDate == date }
    val manual = logged.filter { Instant.ofEpochSecond(it.startUnix).atZone(zone).toLocalDate() == date }
    return activityTimeline(sleep, manual).map { card ->
        HomeActivity(card = card, timeLabel = "${clock(card.startUnix, zone)} – ${clock(card.endUnix, zone)}")
    }
}

private fun recoveryDial(day: DayResult?, noSeconds: Boolean): DialModel {
    val score = day?.recovery?.score
    if (noSeconds || day?.main == null || score == null) return blankDial("Recovery")
    return DialModel(
        title = "Recovery",
        value = score.roundToInt().toString(),
        progress = (score / 100.0).toFloat().coerceIn(0f, 1f),
        tone = when {
            score >= 67.0 -> DialTone.GREEN
            score >= 34.0 -> DialTone.YELLOW
            else -> DialTone.RED
        },
    )
}

private fun strainDial(day: DayResult?, noSeconds: Boolean): DialModel {
    val trimp = day?.strain?.trimp
    if (noSeconds || trimp == null) return blankDial("Strain")
    val dial = approximateStrain21(trimp)
    return DialModel(
        title = "Strain",
        value = String.format(Locale.US, "%.1f", dial),
        progress = (dial / 21.0).toFloat().coerceIn(0f, 1f),
        tone = DialTone.BLUE,
    )
}

private fun sleepDial(day: DayResult?, noSeconds: Boolean): DialModel {
    val seconds = day?.main?.sleepSeconds
    if (noSeconds || seconds == null) return blankDial("Sleep")
    val fraction = seconds.toFloat() / DayRules.SLEEP_REFERENCE_SECONDS.toFloat()
    return DialModel(
        title = "Sleep",
        value = formatDuration(seconds.toLong()),
        progress = fraction.coerceIn(0f, 1f),
        tone = DialTone.PURPLE,
    )
}

private fun blankDial(title: String) = DialModel(
    title = title,
    value = "—",
    progress = 0f,
    tone = DialTone.MUTED,
)

private fun sourceLabel(day: DayResult?, noSeconds: Boolean): String = when {
    noSeconds || day == null -> "no data"
    day.strapSeconds > 0 && day.edgeSeconds > 0 -> "mixed"
    day.edgeSeconds > 0 -> "Edge import"
    else -> "strap"
}

private fun lastSync(live: HomeLive): String {
    val seconds = live.lastSyncUnixSeconds ?: return "—"
    return SYNC.format(Instant.ofEpochSecond(seconds).atZone(live.zone))
}

internal fun formatDuration(seconds: Long): String {
    val whole = seconds.coerceAtLeast(0L)
    val hours = whole / 3600
    val minutes = (whole % 3600) / 60
    return "${hours}h ${minutes}m"
}

private fun formatSteps(steps: Long): String = String.format(Locale.US, "%,d", steps)

private fun clock(unixSeconds: Long, zone: ZoneId): String =
    CLOCK.format(Instant.ofEpochSecond(unixSeconds).atZone(zone))

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
private val SYNC: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
