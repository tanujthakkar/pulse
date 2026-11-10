package app.pulse.metrics

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActivityStatsTest {
    @Test
    fun activitiesAreEarliestFirstAndIncludeSleep() {
        val sleep = episode(start = 1_000, end = 1_000 + 8 * 3600, seconds = 8 * 3600)
        val nap = episode(start = 500, end = 500 + 19 * 60, seconds = 19 * 60)
        val walk = LoggedActivity(id = 2, type = "Walk", startUnix = 100, endUnix = 200)
        val run = LoggedActivity(id = 1, type = "Run", startUnix = 50_000, endUnix = 51_000)
        val cards = activityTimeline(listOf(sleep, nap), listOf(run, walk))
        assertEquals(listOf("Walk", "Sleep", "Run"), cards.map { it.title })
        assertEquals(ActivityKind.SLEEP, cards[1].kind)
        assertEquals(listOf(2L, null, 1L), cards.map { it.manualId })
    }

    @Test
    fun aWindowReportsHeartRateZonesAndApproximateStrain() {
        val start = 10_000L
        val records = listOf(
            second(start, 100, SOURCE_STRAP),
            second(start + 1, 120, SOURCE_STRAP),
            second(start + 2, 140, SOURCE_STRAP),
            second(start + 1, 200, SOURCE_EDGE),
            second(start + 50, 180, SOURCE_STRAP),
        )
        val stats = activityWindow(records, start, start + 10, restingHr = 50)
        assertEquals(10L, stats.durationSeconds)
        assertEquals(3, stats.heartSamples)
        assertEquals(120, stats.averageHr)
        assertEquals(140, stats.maxHr)
        assertEquals(0, stats.zones.below100)
        assertEquals(1, stats.zones.from100)
        assertEquals(1, stats.zones.from120)
        assertEquals(1, stats.zones.from140)
        assertEquals(0, stats.zones.from160)
        assertTrue(stats.zones.trimp!! > 0.0)
        assertEquals(approximateStrain21(stats.zones.trimp!!), stats.approximateStrain)
        assertTrue(stats.chart.none { it.bpm == 200 || it.bpm == 180 })
    }

    @Test
    fun strainStaysEmptyWithoutARestingHeartRate() {
        val stats = activityWindow(listOf(second(5, 110, SOURCE_STRAP)), 0, 20, restingHr = null)
        assertEquals(110, stats.averageHr)
        assertEquals(110, stats.maxHr)
        assertNull(stats.zones.trimp)
        assertNull(stats.approximateStrain)
    }

    private fun episode(start: Long, end: Long, seconds: Int) = SleepEpisode(
        startUnix = start,
        endUnix = end,
        sleepSeconds = seconds,
        spanSeconds = end - start,
        endDate = LocalDate.of(2026, 11, 8),
    )

    private fun second(unix: Long, bpm: Int, source: String) = SecondRecord(
        unixSeconds = unix,
        recordIndex = unix,
        heartRateBpm = bpm,
        rrMillis = emptyList(),
        activityClass = null,
        bandMotion = null,
        stepCounter = 0,
        source = source,
        stepsKnown = false,
    )
}
