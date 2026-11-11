package app.pulse.metrics

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DayWindowTest {
    @Test
    fun eachDayAsksForItsOwnWindow() {
        val zone = ZoneOffset.UTC
        val first = LocalDate.of(2026, 11, 1)
        val last = LocalDate.of(2026, 11, 8)
        val seen = mutableListOf<Pair<Long, Long>>()
        val days = daysByWindow(zone, listOf(last, first), loadWindow = { start, end ->
            seen += start to end
            emptyList()
        })
        assertTrue(days.isEmpty())
        assertEquals(2, seen.size)
        assertTrue(seen[1].first > seen[0].second)
    }

    @Test
    fun onlyTheLatestTwoDatesAreReread() {
        val dates = (1..10).map { LocalDate.of(2026, 11, it) }
        val cached = dates.take(8).toSet()
        assertEquals(
            listOf(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 10)),
            datesToRecompute(dates, cached),
        )
    }

    @Test
    fun threePriorNightsScoreRecoveryWithoutReadingSamples() {
        val earlier = Rhythm(400, 300, 250, 40.0, 200, 50, 52.0)
        val today = earlier.copy(rmssdMs = 50.0, restingHr = 48)
        val recovery = recoveryFromRhythms(today, listOf(earlier, earlier, earlier))
        assertEquals(3, recovery.baselineDays)
        assertTrue(recovery.score != null)
    }
}
