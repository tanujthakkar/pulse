package app.pulse.feature.home

import app.pulse.link.LinkPhase
import app.pulse.metrics.DayResult
import app.pulse.metrics.DayRules
import app.pulse.metrics.approximateStrain21
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

data class DialModel(
    val title: String,
    val value: String,
    val progress: Float,
    val tone: DialTone,
    val detail: String,
)

data class HomeLive(
    val bpm: Int?,
    val phase: LinkPhase = LinkPhase.IDLE,
    val batteryPercent: Int?,
    val lastSyncUnixSeconds: Long?,
    val zone: ZoneId,
    val sampleBanner: String? = null,
)

data class HomeContent(
    val dateLabel: String,
    val canGoPrevious: Boolean,
    val canGoNext: Boolean,
    val sourceLabel: String,
    val recovery: DialModel,
    val strain: DialModel,
    val sleep: DialModel,
    val hrv: String,
    val hrvDetail: String,
    val resting: String,
    val restingDetail: String,
    val sleepStat: String,
    val sleepDetail: String,
    val steps: String,
    val stepsDetail: String,
    val liveValue: String,
    val liveDetail: String,
    val lastSync: String,
    val battery: String,
    val connection: String,
    val sampleBanner: String?,
)

/**
 * Home-screen copy for one local day.
 *
 * Blank dials name the missing input. Recovery under 34 is red, under 67 is
 * yellow, and 67 and above is green. Those cuts are display bands for the
 * Pulse formula, not a clinical threshold.
 */
fun homeContent(
    day: DayResult?,
    date: LocalDate,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    live: HomeLive,
): HomeContent {
    val noSeconds = day == null || (day.strapSeconds == 0 && day.edgeSeconds == 0)
    val recovery = recoveryDial(day, noSeconds)
    val strain = strainDial(day, noSeconds)
    val sleep = sleepDial(day, noSeconds)
    val rhythm = day?.rhythm
    val rmssd = rhythm?.rmssdMs
    val restingHr = rhythm?.restingHr
    val hrv = if (noSeconds || day?.main == null) {
        "—" to "HRV needs a main sleep."
    } else if (rmssd == null) {
        "—" to "Not enough accepted R-R pairs for HRV."
    } else {
        "${rmssd.roundToInt()} ms" to "RMSSD during the main sleep."
    }
    val resting = if (noSeconds || day?.main == null) {
        "—" to "Resting HR needs a main sleep."
    } else if (restingHr == null) {
        "—" to "Not enough heart-rate samples for resting HR."
    } else {
        "$restingHr bpm" to "10th percentile during the main sleep."
    }
    val sleepSeconds = day?.main?.sleepSeconds
    val sleepStat = if (sleepSeconds == null || noSeconds) {
        "—" to "No main sleep ending this day."
    } else {
        formatDuration(sleepSeconds) to "Main sleep. 8 h is only a reference ring."
    }
    val strapSteps = day?.stepIncrease ?: 0L
    val edgeSteps = day?.edgeStepIncrease ?: 0L
    val steps = when {
        noSeconds -> "—" to "No steps stored for this day."
        strapSteps > 0L && edgeSteps > 0L ->
            formatSteps(strapSteps + edgeSteps) to "Strap ${formatSteps(strapSteps)} · Edge ${formatSteps(edgeSteps)}."
        else -> formatSteps(strapSteps + edgeSteps) to "Counted inside each source. Overlap is not added twice."
    }
    val liveValue = live.bpm?.let { "$it" } ?: "—"
    val liveDetail = if (live.bpm == null) {
        "Live heart rate is unavailable."
    } else {
        "bpm from the open session."
    }
    return HomeContent(
        dateLabel = DATE.format(date),
        canGoPrevious = canGoPrevious,
        canGoNext = canGoNext,
        sourceLabel = sourceLabel(day, noSeconds),
        recovery = recovery,
        strain = strain,
        sleep = sleep,
        hrv = hrv.first,
        hrvDetail = hrv.second,
        resting = resting.first,
        restingDetail = resting.second,
        sleepStat = sleepStat.first,
        sleepDetail = sleepStat.second,
        steps = steps.first,
        stepsDetail = steps.second,
        liveValue = liveValue,
        liveDetail = liveDetail,
        lastSync = lastSync(live),
        battery = live.batteryPercent?.let { "Battery: $it%" } ?: "Battery: not stored.",
        connection = "Strap: ${connectionLabel(live.phase)}.",
        sampleBanner = live.sampleBanner,
    )
}

fun connectionLabel(phase: LinkPhase): String = when (phase) {
    LinkPhase.LINKED -> "Connected"
    LinkPhase.SCANNING, LinkPhase.CONNECTING, LinkPhase.BONDING -> "Connecting"
    else -> "Not connected"
}

private fun recoveryDial(day: DayResult?, noSeconds: Boolean): DialModel {
    val score = day?.recovery?.score
    return when {
        noSeconds -> blankDial("Recovery", "No strap or Edge seconds for this day.")
        day?.main == null -> blankDial("Recovery", "Recovery needs a main sleep.")
        score == null -> blankDial("Recovery", "Recovery needs 3 nights.")
        else -> {
            val shown = score.roundToInt()
            DialModel(
                title = "Recovery",
                value = shown.toString(),
                progress = (score / 100.0).toFloat().coerceIn(0f, 1f),
                tone = when {
                    score >= 67.0 -> DialTone.GREEN
                    score >= 34.0 -> DialTone.YELLOW
                    else -> DialTone.RED
                },
                detail = "Pulse formula, 0–100. Not a clinical score.",
            )
        }
    }
}

private fun strainDial(day: DayResult?, noSeconds: Boolean): DialModel {
    val trimp = day?.strain?.trimp
    return when {
        noSeconds -> blankDial("Strain", "No strap or Edge seconds for this day.")
        trimp == null -> blankDial("Strain", "Strain needs a resting heart rate from the main sleep.")
        else -> {
            val dial = approximateStrain21(trimp)
            DialModel(
                title = "Strain",
                value = String.format(Locale.US, "%.1f", dial),
                progress = (dial / 21.0).toFloat().coerceIn(0f, 1f),
                tone = DialTone.BLUE,
                detail = "Approximate 0–21 from Banister TRIMP ${formatTrimp(trimp)}. Not a strain score.",
            )
        }
    }
}

private fun sleepDial(day: DayResult?, noSeconds: Boolean): DialModel {
    val seconds = day?.main?.sleepSeconds
    return when {
        noSeconds -> blankDial("Sleep", "No strap or Edge seconds for this day.")
        seconds == null -> blankDial("Sleep", "No main sleep ending this day.")
        else -> {
            val fraction = seconds.toFloat() / DayRules.SLEEP_REFERENCE_SECONDS.toFloat()
            DialModel(
                title = "Sleep",
                value = formatDuration(seconds),
                progress = fraction.coerceIn(0f, 1f),
                tone = DialTone.PURPLE,
                detail = "Share of an 8 h reference. Not a personal sleep need.",
            )
        }
    }
}

private fun blankDial(title: String, detail: String) = DialModel(
    title = title,
    value = "—",
    progress = 0f,
    tone = DialTone.MUTED,
    detail = detail,
)

private fun sourceLabel(day: DayResult?, noSeconds: Boolean): String = when {
    noSeconds -> "no data"
    day == null -> "no data"
    day.strapSeconds > 0 && day.edgeSeconds > 0 -> "mixed"
    day.edgeSeconds > 0 -> "Edge import"
    else -> "strap"
}

private fun lastSync(live: HomeLive): String {
    val seconds = live.lastSyncUnixSeconds ?: return "Last sync: none stored."
    val text = SYNC.format(Instant.ofEpochSecond(seconds).atZone(live.zone))
    return "Last sync: $text."
}

private fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return "${hours}h ${minutes}m"
}

private fun formatSteps(steps: Long): String = String.format(Locale.US, "%,d", steps)

private fun formatTrimp(trimp: Double): String = String.format(Locale.US, "%.1f", trimp)

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)
private val SYNC: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)
