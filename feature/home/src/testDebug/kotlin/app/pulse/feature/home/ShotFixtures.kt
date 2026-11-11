package app.pulse.feature.home

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import app.cash.paparazzi.Paparazzi
import app.pulse.link.LinkPhase
import app.pulse.metrics.DayResult
import app.pulse.metrics.LoggedActivity
import app.pulse.metrics.Recovery
import app.pulse.metrics.Rhythm
import app.pulse.metrics.SleepEpisode
import app.pulse.metrics.Strain
import java.time.LocalDate
import java.time.ZoneOffset

internal val SHOT_DATE: LocalDate = LocalDate.of(2026, 5, 16)
private val ZONE = ZoneOffset.UTC

internal fun snapPulse(paparazzi: Paparazzi, content: @Composable () -> Unit) {
    paparazzi.snapshot {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = PulsePalette.green,
                onPrimary = Color(0xFF06210F),
                background = PulsePalette.background,
                onBackground = PulsePalette.text,
                surface = PulsePalette.surface,
                onSurface = PulsePalette.text,
            ),
        ) {
            CompositionLocalProvider(LocalContentColor provides PulsePalette.text, content = content)
        }
    }
}

internal fun shotDay(): HomeContent {
    val runStart = SHOT_DATE.atTime(7, 10).toEpochSecond(ZONE)
    val danceStart = SHOT_DATE.atTime(11, 4).toEpochSecond(ZONE)
    val day = storedDay(
        date = SHOT_DATE,
        rmssd = 42.2,
        resting = 49,
        steps = 8_420,
        mean = 72.0,
        sleepSeconds = 7 * 3600 + 20 * 60,
        recovery = 72.0,
        breaths = 15.2,
        skin = 33.6,
    )
    val prior = storedDay(
        date = SHOT_DATE.minusDays(1),
        rmssd = 51.0,
        resting = 52,
        steps = 6_100,
        mean = 68.0,
        sleepSeconds = 7 * 3600,
        recovery = 64.0,
        breaths = 14.8,
        skin = 33.2,
    )
    return homeContent(
        day = day,
        date = SHOT_DATE,
        today = SHOT_DATE,
        logged = listOf(
            LoggedActivity(1, "Running", runStart, SHOT_DATE.atTime(8, 15).toEpochSecond(ZONE)),
            LoggedActivity(2, "Dance", danceStart, SHOT_DATE.atTime(11, 19).toEpochSecond(ZONE)),
        ),
        live = HomeLive(
            bpm = 64,
            phase = LinkPhase.LINKED,
            batteryPercent = 76,
            lastSyncUnixSeconds = null,
            zone = ZONE,
            trace = List(90) { index -> HeartPoint(index * 1000L, 58 + ((index * 3) % 17)) },
        ),
        history = listOf(prior),
        strains = mapOf(runStart to "8.4", danceStart to "3.1"),
    )
}

internal fun shotEmpty(): HomeContent = homeContent(
    day = null,
    date = SHOT_DATE,
    today = SHOT_DATE,
    logged = emptyList(),
    live = HomeLive(
        bpm = null,
        phase = LinkPhase.IDLE,
        batteryPercent = null,
        lastSyncUnixSeconds = null,
        zone = ZONE,
    ),
)

private fun storedDay(
    date: LocalDate,
    rmssd: Double,
    resting: Int,
    steps: Long,
    mean: Double,
    sleepSeconds: Int,
    recovery: Double,
    breaths: Double,
    skin: Double,
): DayResult {
    val start = date.minusDays(1).atTime(22, 40).toEpochSecond(ZONE)
    val end = date.atTime(6, 0).toEpochSecond(ZONE)
    val main = SleepEpisode(start, end, sleepSeconds, end - start, date)
    return DayResult(
        date = date,
        daySeconds = 86_400,
        storedSeconds = 20_000,
        heartSeconds = 18_000,
        anyRrSeconds = 1_000,
        acceptedRrSeconds = 900,
        spanStart = start,
        spanEnd = end,
        missingInsideSpan = 0,
        gapsAtLeastOneMinute = 0,
        largestGapSeconds = 0,
        sleepSeconds = sleepSeconds,
        shortEpisodes = 0,
        shortSleepSeconds = 0,
        episodes = listOf(main),
        main = main,
        rhythm = Rhythm(800, 700, 400, rmssd, 500, resting, 52.0),
        recovery = Recovery(recovery, 4, 40.0, 50.0, null, null, recovery),
        strain = Strain(90.0, 8_000, resting, 100, 40, 10, 0, 0),
        stillSeconds = 1,
        walkSeconds = 1,
        runSeconds = 0,
        otherActivitySeconds = 0,
        stepIncrease = steps,
        edgeStepIncrease = 0,
        stepWraps = 0,
        stepResets = 0,
        stepsAcrossLongGaps = 0,
        strapSeconds = 12_000,
        edgeSeconds = 0,
        strapHeartSeconds = 11_000,
        edgeHeartSeconds = 0,
        strapAcceptedRrSeconds = 500,
        edgeAcceptedRrSeconds = 0,
        sleepDetection = "band-motion",
        edgeNote = null,
        meanHeartBpm = mean,
        breathsPerMinute = breaths,
        sleepSkinTempC = skin,
    )
}
