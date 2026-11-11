package app.pulse.data

import app.pulse.metrics.DayRules
import app.pulse.metrics.SOURCE_EDGE
import app.pulse.metrics.SOURCE_STRAP
import app.pulse.metrics.SecondRecord
import app.pulse.metrics.computeDays
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
    fun coveringDatesUsesBoundsAndDoesNotRequireTheRows() {
        val zone = ZoneOffset.UTC
        val first = LocalDate.of(2026, 11, 8).atStartOfDay(zone).toEpochSecond()
        val last = LocalDate.of(2026, 11, 10).atTime(6, 0).toEpochSecond(zone)
        val dates = coveringDates(
            strapMin = first,
            strapMax = last,
            edgeMinMs = null,
            edgeMaxMs = null,
            zone = zone,
            scoreDayIds = listOf("2026-11-12"),
        )
        assertEquals(
            listOf(
                LocalDate.of(2026, 11, 8),
                LocalDate.of(2026, 11, 9),
                LocalDate.of(2026, 11, 10),
                LocalDate.of(2026, 11, 12),
            ),
            dates,
        )
    }

    @Test
    fun aMissingDeviceRowDoesNotPrintAnAddress() {
        val text = metricsText(ZoneOffset.UTC, null)
        assertTrue(text.contains("No device row is stored."))
        assertTrue(text.contains("Not WHOOP scores"))
        assertFalse(text.contains("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun strapSecondsWinAndEdgeFillsTheRest() {
        val strap = SecondRecord(
            unixSeconds = 1_700_000_000L,
            recordIndex = 4,
            heartRateBpm = 55,
            rrMillis = listOf(500),
            activityClass = 0,
            bandMotion = 0,
            stepCounter = 10,
            source = SOURCE_STRAP,
        )
        val inputs = StoredDayInputs(
            oneHz = listOf(
                oneHz(1_700_000_000_000L, hr = 99, band = 2, steps = 50, activity = 2),
                oneHz(1_700_000_001_000L, hr = 61, band = 2, steps = 51, activity = 1),
                oneHz(1_700_000_001_400L, hr = 70, band = 0, steps = 80, activity = 1),
            ),
            rr = listOf(
                EdgeRrMetricRow(1_700_000_000_000L, 800),
                EdgeRrMetricRow(1_700_000_001_000L, 810),
                EdgeRrMetricRow(1_700_000_001_000L, 820),
            ),
            sleep = listOf(
                EdgeSleepMetricRow("2026-11-01", 1, 10, 20),
                EdgeSleepMetricRow("2026-11-01", 4, 1_700_000_000L, 1_700_000_100L),
            ),
        )
        val assembly = assembleDays(listOf(strap), inputs)
        assertEquals(2, assembly.records.size)
        val keptStrap = assembly.records.first { it.unixSeconds == 1_700_000_000L }
        assertEquals(55, keptStrap.heartRateBpm)
        assertEquals(listOf(500), keptStrap.rrMillis)
        assertEquals(SOURCE_STRAP, keptStrap.source)
        val keptEdge = assembly.records.first { it.unixSeconds == 1_700_000_001L }
        assertEquals(61, keptEdge.heartRateBpm)
        assertEquals(listOf(810, 820), keptEdge.rrMillis)
        assertEquals(DayRules.SLEEP, keptEdge.bandMotion)
        assertEquals(1, keptEdge.activityClass)
        assertEquals(51, keptEdge.stepCounter)
        assertEquals(SOURCE_EDGE, keptEdge.source)
        assertEquals(4L, assembly.windows.single().algoVersion)
        assertNull(edgeHeartBpm(hr = 10, hrValid = 1))
        assertNull(edgeHeartBpm(hr = 80, hrValid = 0))
        assertEquals(1_700_000_000L, epochSeconds(1_700_000_000_000L))
    }

    @Test
    fun anEdgeOnlyNightFeedsThePulseFormulas() {
        val start = LocalDate.of(2026, 7, 4).atStartOfDay(ZoneOffset.UTC).toEpochSecond() + 3600
        val oneHz = (0 until 1200).map { index ->
            oneHz(
                tsMs = (start + index) * 1000,
                hr = 50,
                band = DayRules.SLEEP.toLong(),
                steps = index.toLong(),
                activity = 0,
            )
        }
        val rr = (0 until 1200).map { index ->
            val millis = if (index % 2 == 0) 1000L else 1100L
            EdgeRrMetricRow((start + index) * 1000, millis)
        }
        val assembly = assembleDays(emptyList(), StoredDayInputs(oneHz = oneHz, rr = rr))
        val day = computeDays(assembly.records, ZoneOffset.UTC).days.single()
        val rhythm = day.rhythm!!
        assertEquals(1200, day.edgeSeconds)
        assertEquals(0, day.strapSeconds)
        assertEquals(100.0, rhythm.rmssdMs!!, 0.001)
        assertEquals(50, rhythm.restingHr)
        assertEquals("band-motion", day.sleepDetection)
        assertEquals(1199L, day.edgeStepIncrease)
    }

    private fun oneHz(
        tsMs: Long,
        hr: Long,
        band: Long?,
        steps: Long?,
        activity: Long?,
    ) = EdgeOneHzMetricRow(
        tsMs = tsMs,
        hr = hr,
        hrValid = 1,
        activityClass = activity,
        stepCount = steps,
        bandSleepState = band,
    )

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
