package app.pulse.feature.home

import app.pulse.metrics.BodyProfile
import app.pulse.metrics.DayResult
import app.pulse.metrics.DayRules
import app.pulse.metrics.Recovery
import app.pulse.metrics.Rhythm
import app.pulse.metrics.SecondRecord
import app.pulse.metrics.Sex
import app.pulse.metrics.SleepEpisode
import app.pulse.metrics.Strain
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepReportTest {
    @Test
    fun aNightWithHeartRateEstimatesStagesAndNeed() {
        val zone = ZoneOffset.UTC
        val date = LocalDate.of(2026, 5, 16)
        val start = date.minusDays(1).atTime(22, 30).toEpochSecond(zone)
        val seconds = 40 * 60
        val records = (0 until seconds).map { offset ->
            val hr = when {
                offset < 10 * 60 -> 46
                offset < 20 * 60 -> 51
                offset < 30 * 60 -> 54
                else -> if (offset % 2 == 0) 56 else 60
            }
            val awake = offset in 15 * 60 until 15 * 60 + 40
            SecondRecord(
                unixSeconds = start + offset,
                recordIndex = offset.toLong(),
                heartRateBpm = if (awake) 80 else hr,
                rrMillis = emptyList(),
                activityClass = null,
                bandMotion = if (awake) 0 else DayRules.SLEEP,
                stepCounter = 0,
            )
        }
        val history = listOf(
            stored(date.minusDays(3), start - 3 * 86400, 8 * 3600),
            stored(date.minusDays(2), start - 2 * 86400, 7 * 3600),
            stored(date.minusDays(1), start - 86400, 8 * 3600),
            stored(date, start, seconds),
        )
        val report = buildSleepReport(
            start = start,
            end = start + seconds - 1,
            zone = zone,
            records = records,
            history = history,
            wakeDate = date,
            batteryPercent = 54,
        )
        assertEquals("SAT, MAY 16", report.dateLabel)
        assertTrue(report.stages != null)
        assertTrue(report.stageNote.contains("estimated"))
        assertEquals(1, report.wakeEvents)
        assertTrue((report.performancePercent ?: 0) in 1..100)
        assertTrue(report.week.size == 7)
        assertTrue(report.week.last().selected)
        assertEquals("Sat\n16", report.week.last().label)
    }

    @Test
    fun vo2AndCaloriesStayBlankUntilTheProfileIsSaved() {
        val day = stored(LocalDate.of(2026, 5, 16), 1_700_000_000L, 8 * 3600)
        val blank = homeContent(
            day = day,
            date = day.date,
            today = day.date,
            logged = emptyList(),
            live = HomeLive(null, batteryPercent = null, lastSyncUnixSeconds = null, zone = ZoneOffset.UTC),
        )
        assertEquals("--", blank.dashboard.first { it.tag == "dash_vo2" }.value)
        assertTrue(blank.dashboard.first { it.tag == "dash_vo2" }.note!!.contains("More"))
        val filled = homeContent(
            day = day,
            date = day.date,
            today = day.date,
            logged = emptyList(),
            live = HomeLive(null, batteryPercent = null, lastSyncUnixSeconds = null, zone = ZoneOffset.UTC),
            profile = BodyProfile(Sex.MALE, 30, 180, 80.0),
        )
        assertEquals("59.6", filled.dashboard.first { it.tag == "dash_vo2" }.value)
        assertTrue(filled.dashboard.first { it.tag == "dash_calories" }.value != "--")
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
        strain = Strain(80.0, slept, 48, 10, 0, 0, 0, 0),
        stillSeconds = 0,
        walkSeconds = 0,
        runSeconds = 0,
        otherActivitySeconds = 0,
        stepIncrease = 1000,
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
}
