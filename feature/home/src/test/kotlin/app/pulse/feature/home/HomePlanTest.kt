package app.pulse.feature.home

import app.pulse.metrics.DayResult
import app.pulse.metrics.LoggedActivity
import app.pulse.metrics.Recovery
import app.pulse.metrics.SleepEpisode
import app.pulse.metrics.Strain
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomePlanTest {
    @Test
    fun sleepThatEndsTodayPrecedesALaterWorkout() {
        val zone = ZoneOffset.UTC
        val date = LocalDate.of(2026, 11, 8)
        val start = date.atStartOfDay(zone).toEpochSecond()
        val sleep = SleepEpisode(
            startUnix = start - 8 * 3600,
            endUnix = start + 6 * 3600,
            sleepSeconds = 8 * 3600,
            spanSeconds = 14 * 3600L,
            endDate = date,
        )
        val yesterday = LoggedActivity(id = 1, type = "Walk", startUnix = start - 10 * 3600, endUnix = start - 9 * 3600)
        val run = LoggedActivity(id = 2, type = "Run", startUnix = start + 18 * 3600, endUnix = start + 19 * 3600)
        val rows = activitiesFor(
            day = day(date, sleep),
            date = date,
            logged = listOf(run, yesterday),
            zone = zone,
        )
        assertEquals(listOf("Sleep", "Run"), rows.map { it.card.title })
        assertEquals(listOf(null, 2L), rows.map { it.card.manualId })
        assertEquals("[Sat]", rows.first().dayPrefix)
        assertNull(rows[1].dayPrefix)
    }

    @Test
    fun browsingAMonthDoesNotLoadUntilADayIsPicked() {
        val loaded = ArrayList<LocalDate>()
        val selection = HomeDateSelection(YearMonth.of(2026, 11)) { loaded += it }
        selection.browse(YearMonth.of(2026, 12))
        selection.browse(YearMonth.of(2027, 1))
        assertEquals(0, selection.loads)
        assertEquals(YearMonth.of(2027, 1), selection.visibleMonth)
        selection.pick(LocalDate.of(2026, 12, 3))
        assertEquals(listOf(LocalDate.of(2026, 12, 3)), loaded)
        assertEquals(1, selection.loads)
        assertEquals(YearMonth.of(2026, 12), selection.visibleMonth)
    }

    private fun day(date: LocalDate, sleep: SleepEpisode) = DayResult(
        date = date,
        daySeconds = 86_400,
        storedSeconds = 1,
        heartSeconds = 1,
        anyRrSeconds = 0,
        acceptedRrSeconds = 0,
        spanStart = sleep.startUnix,
        spanEnd = sleep.endUnix,
        missingInsideSpan = 0,
        gapsAtLeastOneMinute = 0,
        largestGapSeconds = 0,
        sleepSeconds = sleep.sleepSeconds,
        shortEpisodes = 0,
        shortSleepSeconds = 0,
        episodes = listOf(sleep),
        main = sleep,
        rhythm = null,
        recovery = Recovery(null, 0, null, null, null, null, null),
        strain = Strain(null, 0, null, 0, 0, 0, 0, 0),
        stillSeconds = 0,
        walkSeconds = 0,
        runSeconds = 0,
        otherActivitySeconds = 0,
        stepIncrease = 0,
        edgeStepIncrease = 0,
        stepWraps = 0,
        stepResets = 0,
        stepsAcrossLongGaps = 0,
        strapSeconds = 1,
        edgeSeconds = 0,
        strapHeartSeconds = 1,
        edgeHeartSeconds = 0,
        strapAcceptedRrSeconds = 0,
        edgeAcceptedRrSeconds = 0,
        sleepDetection = "band-motion",
        edgeNote = null,
    )
}
