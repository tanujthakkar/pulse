package app.pulse.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.pulse.metrics.ChangeTone
import app.pulse.metrics.ChartRange
import app.pulse.metrics.DayResult
import app.pulse.metrics.DayRules
import app.pulse.metrics.PlotPoint
import app.pulse.metrics.Recovery
import app.pulse.metrics.Rhythm
import app.pulse.metrics.SecondRecord
import app.pulse.metrics.SleepEpisode
import app.pulse.metrics.Strain
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test

class SleepMetricShots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1440,
            screenHeight = 3120,
            xdpi = 500,
            ydpi = 500,
        ),
        renderingMode = RenderingMode.NORMAL,
        useDeviceResolution = true,
    )

    private val report = sampleReport()

    @Test
    fun sleepPerformance() = snap {
        Column(Modifier.fillMaxSize().background(PulsePalette.background).padding(PulseSpace.md)) {
            SleepHeader(report) {}
            SleepPerformanceBlock(report)
            SleepBreakdownCard(report)
        }
    }

    @Test
    fun lastNight() = snap {
        Column(Modifier.fillMaxSize().background(PulsePalette.background).padding(PulseSpace.md)) {
            LastNightBlock(report)
            HoursNeededCard(report)
        }
    }

    @Test
    fun sleepTrends() = snap {
        Column(Modifier.fillMaxSize().background(PulsePalette.background).padding(PulseSpace.md)) {
            WeeklyTrendsBlock(report) { _, _ -> }
        }
    }

    @Test
    fun metricWeek() = snap {
        MetricDetailScreen(
            state = MetricDetailState(
                title = "RESTING HEART RATE",
                note = "bpm",
                range = ChartRange.WEEK,
                points = (0 until 7).map { offset ->
                    val date = LocalDate.of(2026, 5, 10).plusDays(offset.toLong())
                    PlotPoint(
                        label = date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() } + "\n" + date.dayOfMonth,
                        value = listOf(52.0, 51.0, 50.0, 49.0, 50.0, 48.0, 47.0)[offset],
                        highlighted = offset == 6,
                    )
                },
                averageText = "50",
                changeText = "-6% vs previous",
                tone = ChangeTone.IMPROVED,
                bandLow = 47.5,
                bandHigh = 52.5,
                line = false,
                yMin = 44.0,
                yMax = 56.0,
                unit = "bpm",
            ),
            onBack = {},
            onRange = {},
        )
    }

    private fun snap(content: @androidx.compose.runtime.Composable () -> Unit) = snapPulse(paparazzi, content)
}

private fun sampleReport(): SleepReport {
    val zone = ZoneOffset.UTC
    val date = LocalDate.of(2026, 5, 16)
    val start = date.minusDays(1).atTime(22, 30).toEpochSecond(zone)
    val seconds = 3 * 3600
    val records = (0 until seconds).map { offset ->
        val hr = when {
            offset % (90 * 60) < 20 * 60 -> 46
            offset % (90 * 60) < 50 * 60 -> 52
            offset % (90 * 60) < 70 * 60 -> if (offset % 2 == 0) 56 else 60
            else -> 51
        }
        val awake = offset in 40 * 60 until 40 * 60 + 45 || offset in 2 * 3600 until 2 * 3600 + 40
        SecondRecord(
            unixSeconds = start + offset,
            recordIndex = offset.toLong(),
            heartRateBpm = if (awake) 78 else hr,
            rrMillis = emptyList(),
            activityClass = null,
            bandMotion = if (awake) 0 else DayRules.SLEEP,
            stepCounter = 0,
        )
    }
    val history = (0 until 7).map { ago ->
        val day = date.minusDays((6 - ago).toLong())
        val nightStart = day.minusDays(1).atTime(22, 0).plusMinutes((15 + ago * 8).toLong()).toEpochSecond(zone)
        val slept = (6 * 3600) + ago * 600
        stored(day, nightStart, slept)
    }
    val extras = history.filter { it.date != date }.associate { day ->
        val main = day.main!!
        day.date to (0 until 40 * 60).map { offset ->
            val hr = when {
                offset < 10 * 60 -> 46
                offset < 25 * 60 -> 52
                else -> if (offset % 2 == 0) 56 else 60
            }
            SecondRecord(main.startUnix + offset, offset.toLong(), hr, emptyList(), null, DayRules.SLEEP, 0)
        }
    }
    return buildSleepReport(start, start + seconds - 1, zone, records, history, date, 54, extras)
}

private fun stored(date: LocalDate, start: Long, slept: Int) = DayResult(
    date = date,
    daySeconds = 86_400,
    storedSeconds = slept,
    heartSeconds = slept,
    anyRrSeconds = 0,
    acceptedRrSeconds = 0,
    spanStart = start,
    spanEnd = start + slept,
    missingInsideSpan = 0,
    gapsAtLeastOneMinute = 0,
    largestGapSeconds = 0,
    sleepSeconds = slept,
    shortEpisodes = 0,
    shortSleepSeconds = 0,
    episodes = listOf(SleepEpisode(start, start + slept - 1, slept, slept.toLong(), date)),
    main = SleepEpisode(start, start + slept - 1, slept, slept.toLong(), date),
    rhythm = Rhythm(400, 400, 300, 42.0, 400, 48, 52.0),
    recovery = Recovery(70.0, 4, 40.0, 50.0, null, null, 70.0),
    strain = Strain(90.0, slept, 48, 10, 0, 0, 0, 0),
    stillSeconds = 0,
    walkSeconds = 0,
    runSeconds = 0,
    otherActivitySeconds = 0,
    stepIncrease = 4000,
    edgeStepIncrease = 0,
    stepWraps = 0,
    stepResets = 0,
    stepsAcrossLongGaps = 0,
    strapSeconds = slept,
    edgeSeconds = 0,
    strapHeartSeconds = slept,
    edgeHeartSeconds = 0,
    strapAcceptedRrSeconds = 0,
    edgeAcceptedRrSeconds = 0,
    sleepDetection = "band-motion",
    edgeNote = null,
)
