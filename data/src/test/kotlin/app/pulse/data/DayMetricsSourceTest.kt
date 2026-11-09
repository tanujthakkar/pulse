package app.pulse.data

import app.pulse.metrics.DayRules
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DayMetricsSourceTest {
    @Test
    fun positiveTicksBecomeMillisecondsAndTheCountLimitsTheList() {
        assertEquals(1, rrTickToMillis(1))
        assertEquals(500, rrTickToMillis(512))
        assertEquals(1000, rrTickToMillis(1024))
        val row = metricRow(rrCount = 2, ticks = listOf(1024, 0, 512, 700))
        val record = row.toSecondRecord()
        assertEquals(listOf(1000), record.rrMillis)
        assertEquals(60, record.heartRateBpm)
        assertEquals(1, record.activityClass)
        assertEquals(DayRules.SLEEP, record.bandMotion)
        assertEquals(40, record.stepCounter)
    }

    @Test
    fun aMissingDeviceRowDoesNotPrintAnAddress() {
        val text = metricsText(ZoneOffset.UTC, null)
        assertTrue(text.contains("No device row is stored."))
        assertTrue(text.contains("Not WHOOP scores"))
        assertFalse(text.contains("AA:BB:CC:DD:EE:FF"))
    }

    private fun metricRow(rrCount: Int, ticks: List<Int?>) = R18MetricRow(
        unixSeconds = 1_700_000_000L,
        recordIndex = 3,
        heartRateBpm = 60,
        rrCount = rrCount,
        rrTick0 = ticks[0],
        rrTick1 = ticks[1],
        rrTick2 = ticks[2],
        rrTick3 = ticks[3],
        activityClass = 1,
        restState = DayRules.SLEEP,
        stepCounter = 40,
    )
}
