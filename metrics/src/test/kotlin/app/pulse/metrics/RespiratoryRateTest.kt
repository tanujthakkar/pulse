package app.pulse.metrics

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RespiratoryRateTest {
    @Test
    fun aRespiratoryWaveReturnsBreathsPerMinute() {
        val intervals = List(240) { index ->
            val wave = sin(2.0 * PI * (index % 4) / 4.0)
            val noise = (index * 17 % 11) - 5
            (1000.0 + wave * 40.0 + noise).toInt()
        }
        val rate = respiratoryRateFromRr(intervals)
        assertTrue(rate != null)
        assertTrue(abs(rate!! - 15.0) < 1.5, rate.toString())
    }

    @Test
    fun aFlatSeriesHasNoRespiratoryRate() {
        assertNull(respiratoryRateFromRr(List(240) { 1000 }))
        assertNull(respiratoryRateFromRr(List(10) { 1000 }))
    }

    @Test
    fun aNightKeepsSkinTemperatureAndDayAverageHeartRate() {
        val zone = ZoneOffset.UTC
        val date = LocalDate.of(2026, 5, 16)
        val start = date.atStartOfDay(zone).toEpochSecond()
        val sleep = List(1_200) { index ->
            val wave = sin(2.0 * PI * (index % 4) / 4.0)
            second(start + index, index, heart = 50, rr = (1000.0 + wave * 40.0).toInt(), skin = 33.5f)
        }
        val day = List(60) { index ->
            second(start + 20_000L + index * 60L, 2_000 + index, heart = 80, rr = null, skin = null)
        }
        val book = computeDays(sleep + day, zone)
        val result = book.days.first { it.date == date }
        assertEquals(33.5, result.sleepSkinTempC!!, 0.05)
        val breaths = result.breathsPerMinute
        assertTrue(breaths != null)
        assertTrue(abs(breaths!! - 15.0) < 1.5, breaths.toString())
        assertTrue(result.meanHeartBpm!! > 50.0)
        assertTrue(result.meanHeartBpm!! < 80.0)
    }

    private fun second(unix: Long, index: Int, heart: Int, rr: Int?, skin: Float?) = SecondRecord(
        unixSeconds = unix,
        recordIndex = index.toLong(),
        heartRateBpm = heart,
        rrMillis = if (rr == null) emptyList() else listOf(rr),
        activityClass = 0,
        bandMotion = if (rr == null) 1 else DayRules.SLEEP,
        stepCounter = index,
        skinTempC = skin,
    )
}
