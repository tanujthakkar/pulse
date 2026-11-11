package app.pulse.feature.home

import app.pulse.data.ActivityLog
import app.pulse.data.DayMetricsSource
import app.pulse.data.DaySnapshot
import app.pulse.metrics.DayBook
import app.pulse.metrics.DayResult
import app.pulse.metrics.LoggedActivity
import app.pulse.metrics.Recovery
import app.pulse.metrics.Rhythm
import app.pulse.metrics.SecondRecord
import app.pulse.metrics.SleepEpisode
import app.pulse.metrics.Strain
import app.pulse.metrics.activityWindow
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityDetailTest {
    @Test
    fun aWorkoutUsesHeartRateInItsWindow() {
        val start = LocalDate.of(2026, 11, 8).atStartOfDay(ZoneOffset.UTC).toEpochSecond() + 10 * 3600
        val records = listOf(
            heart(start, 100),
            heart(start + 1, 120),
            heart(start + 2, 140),
        )
        val stats = activityWindow(records, start, start + 3600, restingHr = 50)
        val detail = workoutDetail("Run", stats)
        assertEquals("Run", detail.title)
        assertEquals("1h 0m", detail.rows.first { it.first == "Duration" }.second)
        assertEquals("120 bpm", detail.rows.first { it.first == "Average HR" }.second)
        assertEquals("140 bpm", detail.rows.first { it.first == "Max HR" }.second)
        assertTrue(detail.rows.first { it.first == "Strain" }.second.contains("approximate"))
        assertTrue(detail.note.contains("approximate"))
        assertEquals(3, detail.chart.size)
    }

    @Test
    fun sleepShowsOnsetOffsetHrvAndNoStages() {
        val zone = ZoneOffset.UTC
        val start = LocalDate.of(2026, 11, 8).atStartOfDay(zone).toEpochSecond() - 2 * 3600
        val end = start + 8 * 3600
        val rhythm = Rhythm(
            intervals = 10,
            accepted = 10,
            pairs = 9,
            rmssdMs = 42.2,
            heartSamples = 8,
            restingHr = 48,
            meanHr = 52.0,
        )
        val detail = sleepDetail(start, end, zone, rhythm, edgeProvided = true)
        assertEquals("8h 0m", detail.rows.first { it.first == "Duration" }.second)
        assertTrue(detail.rows.first { it.first == "Onset" }.second.contains("Edge-provided"))
        assertEquals("42 ms", detail.rows.first { it.first == "HRV" }.second)
        assertEquals("48 bpm", detail.rows.first { it.first == "Resting HR" }.second)
        assertEquals("Stages are not in the stored history.", detail.note)
    }

    @Test
    fun sleepBadgeAndDetailShareTheSampleCountForAnInclusiveNight() {
        val zone = ZoneOffset.UTC
        val start = LocalDate.of(2026, 11, 8).atTime(23, 0).toEpochSecond(zone)
        val asleep = 7 * 3600
        val end = start + asleep - 1
        val date = LocalDate.of(2026, 11, 9)
        val episode = SleepEpisode(start, end, asleep, asleep.toLong(), date)
        val stored = day(date, start).copy(
            episodes = listOf(episode),
            main = episode,
            sleepSeconds = asleep,
            strapSeconds = asleep,
        )
        val content = homeContent(
            day = stored,
            date = date,
            today = date,
            logged = emptyList(),
            live = HomeLive(bpm = null, batteryPercent = null, lastSyncUnixSeconds = null, zone = zone),
        )
        val tile = content.activities.single().tile
        val detail = sleepDetail(
            start,
            end,
            zone,
            stored.rhythm,
            false,
            asleepSeconds(episode.sleepSeconds, start, end),
        )
        assertEquals("6h 59m", formatDuration(end - start))
        assertEquals("7h 0m", tile)
        assertEquals(tile, detail.rows.first { it.first == "Duration" }.second)
    }

    @Test
    fun theLoaderUsesTheDaysRestingHeartRate() {
        val zone = ZoneOffset.UTC
        val date = LocalDate.of(2026, 11, 8)
        val start = date.atStartOfDay(zone).toEpochSecond() + 12 * 3600
        val records = listOf(heart(start, 100), heart(start + 1, 140))
        val days = FakeDays(records, day(date, start))
        val detail = loadActivityDetail(
            "MANUAL",
            start,
            start + 600,
            days,
            FakeLog(listOf(LoggedActivity(4, "Run", start, start + 600))),
        )
        assertEquals("Run", detail.title)
        assertEquals("120 bpm", detail.rows.first { it.first == "Average HR" }.second)
        assertTrue(detail.rows.first { it.first == "Strain" }.second.contains("approximate"))
        assertEquals(0, days.snapshots)
        assertEquals(1, days.windows)
    }

    @Test
    fun sleepDetailReadsTheWindowAndDoesNotRecomputeEveryDay() {
        val zone = ZoneOffset.UTC
        val start = LocalDate.of(2026, 11, 8).atTime(23, 0).toEpochSecond(zone)
        val asleep = 3
        val records = (0 until asleep).map { offset ->
            heart(start + offset, 48).copy(bandMotion = app.pulse.metrics.DayRules.SLEEP)
        }
        val days = FakeDays(records, day(LocalDate.of(2026, 11, 9), start))
        val report = loadSleepReport(start, start + 7 * 3600, days)
        assertEquals(0, days.snapshots)
        assertEquals(1, days.windows)
        assertEquals(asleep.toLong(), report.asleepSeconds)
        assertTrue(report.stageNote.contains("not available"))
    }

    @Test
    fun overlappingSleepRowsMergeIntoTheLongerNight() {
        val zone = ZoneOffset.UTC
        val date = LocalDate.of(2026, 11, 9)
        val start = date.minusDays(1).atTime(23, 0).toEpochSecond(zone)
        val partial = SleepEpisode(start, start + 30 * 60 - 1, 30 * 60, 30 * 60L, date)
        val full = SleepEpisode(start, start + 7 * 3600 - 1, 7 * 3600, (7 * 3600).toLong(), date)
        val stored = day(date, start).copy(
            episodes = listOf(partial, full),
            main = full,
            strapSeconds = 7 * 3600,
        )
        val content = homeContent(
            day = stored,
            date = date,
            today = date,
            logged = emptyList(),
            live = HomeLive(bpm = null, batteryPercent = null, lastSyncUnixSeconds = null, zone = zone),
        )
        assertEquals(1, content.activities.size)
        assertEquals("7h 0m", content.activities.single().tile)
        assertEquals("11:00 PM", content.activities.single().startLabel)
    }

    private fun heart(unix: Long, bpm: Int) = SecondRecord(
        unixSeconds = unix,
        recordIndex = unix,
        heartRateBpm = bpm,
        rrMillis = emptyList(),
        activityClass = null,
        bandMotion = null,
        stepCounter = 0,
        source = "strap",
        stepsKnown = false,
    )

    private fun day(date: LocalDate, start: Long) = DayResult(
        date = date,
        daySeconds = 86_400,
        storedSeconds = 2,
        heartSeconds = 2,
        anyRrSeconds = 0,
        acceptedRrSeconds = 0,
        spanStart = start,
        spanEnd = start + 2,
        missingInsideSpan = 0,
        gapsAtLeastOneMinute = 0,
        largestGapSeconds = 0,
        sleepSeconds = 8 * 3600,
        shortEpisodes = 0,
        shortSleepSeconds = 0,
        episodes = emptyList(),
        main = SleepEpisode(start, start + 8 * 3600, 8 * 3600, 8 * 3600L, date),
        rhythm = Rhythm(1, 1, 1, 40.0, 1, 50, 50.0),
        recovery = Recovery(70.0, 3, 40.0, 50.0, null, null, 70.0),
        strain = Strain(1.0, 2, 50, 0, 1, 0, 1, 0),
        stillSeconds = 0,
        walkSeconds = 0,
        runSeconds = 0,
        otherActivitySeconds = 0,
        stepIncrease = 0,
        edgeStepIncrease = 0,
        stepWraps = 0,
        stepResets = 0,
        stepsAcrossLongGaps = 0,
        strapSeconds = 2,
        edgeSeconds = 0,
        strapHeartSeconds = 2,
        edgeHeartSeconds = 0,
        strapAcceptedRrSeconds = 0,
        edgeAcceptedRrSeconds = 0,
        sleepDetection = "band-motion",
        edgeNote = null,
    )

    private class FakeDays(
        private val records: List<SecondRecord>,
        private val day: DayResult,
    ) : DayMetricsSource {
        var snapshots = 0
        var windows = 0

        override fun report(zone: ZoneId): String = ""

        override fun snapshot(zone: ZoneId): DaySnapshot {
            snapshots += 1
            return DaySnapshot(DayBook("UTC", listOf(day), null), null, null)
        }

        override fun cachedSnapshot(): DaySnapshot =
            DaySnapshot(DayBook("UTC", listOf(day), null), null, null)

        override fun windowRecords(startUnix: Long, endUnix: Long): List<SecondRecord> {
            windows += 1
            return records.filter { it.unixSeconds in startUnix until endUnix }
        }
    }

    private class FakeLog(private val rows: List<LoggedActivity>) : ActivityLog {
        override fun all(): List<LoggedActivity> = rows

        override fun save(id: Long?, type: String, startUnix: Long, endUnix: Long) = Unit

        override fun delete(id: Long) = Unit
    }
}
