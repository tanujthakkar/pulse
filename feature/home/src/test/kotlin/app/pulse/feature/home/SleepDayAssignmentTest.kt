package app.pulse.feature.home

import app.pulse.metrics.DayRules
import app.pulse.metrics.SecondRecord
import app.pulse.metrics.daysByWindow
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A night that starts in the evening belongs to the morning it ends.
 * The start day keeps no row, including the hour before midnight.
 */
class SleepDayAssignmentTest {
    @Test
    fun aSleepThatCrossesMidnightShowsOnlyOnTheWakeDay() {
        val zone = ZoneOffset.UTC
        val startDay = LocalDate.of(2026, 11, 9)
        val wakeDay = LocalDate.of(2026, 11, 10)
        val start = startDay.atTime(23, 0).toEpochSecond(zone)
        val asleep = 3 * 3600
        val records = List(asleep) { offset ->
            SecondRecord(
                unixSeconds = start + offset,
                recordIndex = offset.toLong(),
                heartRateBpm = 48,
                rrMillis = emptyList(),
                activityClass = 0,
                bandMotion = DayRules.SLEEP,
                stepCounter = 0,
            )
        }
        val days = daysByWindow(zone, listOf(startDay, wakeDay), loadWindow = { from, until ->
            records.filter { it.unixSeconds >= from && it.unixSeconds < until }
        })
        val live = HomeLive(bpm = null, batteryPercent = null, lastSyncUnixSeconds = null, zone = zone)
        val startHome = homeContent(
            day = days.first { it.date == startDay },
            date = startDay,
            today = wakeDay,
            logged = emptyList(),
            live = live,
        )
        val wakeHome = homeContent(
            day = days.first { it.date == wakeDay },
            date = wakeDay,
            today = wakeDay,
            logged = emptyList(),
            live = live,
        )

        assertTrue(startHome.activities.isEmpty())
        assertEquals(1, wakeHome.activities.size)
        val row = wakeHome.activities.single()
        assertEquals(formatDuration(asleep.toLong()), row.tile)
        assertEquals("11:00 PM", row.startLabel)
        assertEquals("1:59 AM", row.endLabel)
    }
}
