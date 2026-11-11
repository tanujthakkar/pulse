package app.pulse.feature.home

import app.pulse.metrics.DayRules
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SummaryRingTest {
    @Test
    fun recoveryColorUsesTheHigherBandAtEachCut() {
        assertEquals(RecoveryRingBand.RED, recoveryRingBand(0.0))
        assertEquals(RecoveryRingBand.RED, recoveryRingBand(29.9))
        assertEquals(RecoveryRingBand.YELLOW, recoveryRingBand(30.0))
        assertEquals(RecoveryRingBand.YELLOW, recoveryRingBand(74.9))
        assertEquals(RecoveryRingBand.GREEN, recoveryRingBand(75.0))
        assertEquals(RecoveryRingBand.GREEN, recoveryRingBand(100.0))
        assertEquals("29%" to DialTone.RED, recoveryRingDisplay(29.4))
        assertEquals("30%" to DialTone.YELLOW, recoveryRingDisplay(29.5))
        assertEquals("30%" to DialTone.YELLOW, recoveryRingDisplay(30.0))
        assertEquals("74%" to DialTone.YELLOW, recoveryRingDisplay(74.4))
        assertEquals("75%" to DialTone.GREEN, recoveryRingDisplay(74.6))
        assertEquals("75%" to DialTone.GREEN, recoveryRingDisplay(75.0))
    }

    @Test
    fun sleepPercentUsesTheEightHourReference() {
        assertEquals(8 * 3600, DayRules.SLEEP_REFERENCE_SECONDS)
        assertEquals(0, sleepPerformancePercent(0))
        assertEquals(75, sleepPerformancePercent(6 * 3600))
        assertEquals(94, sleepPerformancePercent(7 * 3600 + 30 * 60))
        assertEquals(100, sleepPerformancePercent(8 * 3600))
        assertEquals(100, sleepPerformancePercent(9 * 3600))
    }

    @Test
    fun previousDayPrefixUsesThatStartWeekday() {
        val zone = ZoneOffset.UTC
        val saturday = LocalDate.of(2026, 5, 16)
        assertEquals(java.time.DayOfWeek.SATURDAY, saturday.dayOfWeek)
        val fridayNight = saturday.minusDays(1).atTime(22, 30).toEpochSecond(zone)
        assertEquals("[Fri]", previousDayPrefix(fridayNight, saturday, zone))
        val sameDay = saturday.atTime(7, 10).toEpochSecond(zone)
        assertNull(previousDayPrefix(sameDay, saturday, zone))
        val thursday = saturday.minusDays(2).atTime(23, 0).toEpochSecond(zone)
        assertNull(previousDayPrefix(thursday, saturday, zone))
    }
}
