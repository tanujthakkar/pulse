package app.pulse.metrics

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class PublishedEstimatesTest {
    private val profile = BodyProfile(Sex.MALE, 30, 180, 80.0)

    @Test
    fun vo2UsesUthAndTanakaAndStaysBlankWithoutInputs() {
        assertEquals(187.0, tanakaMaxHeartRate(30), 0.001)
        val vo2 = uthVo2Max(30, 50)
        assertEquals(15.3 * 187.0 / 50.0, vo2!!, 0.001)
        assertNull(uthVo2Max(30, 0))
        assertNull(uthVo2Max(10, 50))
    }

    @Test
    fun caloriesUseMifflinPlusTheKeytelDifferenceAboveResting() {
        assertEquals(1780.0, mifflinStJeorBmr(profile), 0.001)
        val female = profile.copy(sex = Sex.FEMALE)
        assertEquals(1614.0, mifflinStJeorBmr(female), 0.001)
        val maleAt120 = keytelKjPerMinute(profile, 120.0)
        assertEquals(-55.0969 + 0.6309 * 120 + 0.1988 * 80 + 0.2017 * 30, maleAt120, 0.001)
        val perMinute = keytelIncrementalKcalPerMinute(profile, 70.0, 60)
        assertEquals(0.6309 * 10.0 / 4.184, perMinute, 0.001)
        val day = estimatedDayKcal(profile, meanHr = 70.0, heartSeconds = 3600, restingHr = 60)
        assertEquals(1780.0 + perMinute * 60.0, day, 0.01)
        assertEquals(1780.0, estimatedDayKcal(profile, meanHr = null, heartSeconds = 0, restingHr = null), 0.001)
    }

    @Test
    fun sleepNeedIsBaselinePlusStrainPlusCappedDebt() {
        val baseline = personalSleepBaselineSeconds(listOf(7 * 3600, 8 * 3600, 9 * 3600))
        assertEquals(8 * 3600, baseline)
        assertEquals(DayRules.SLEEP_REFERENCE_SECONDS, personalSleepBaselineSeconds(listOf(7 * 3600)))
        assertEquals(3600, strainSleepAdjustmentSeconds(21.0))
        assertEquals(0, strainSleepAdjustmentSeconds(null))
        val debt = sleepDebtAdditionSeconds(listOf(2 * 3600, 2 * 3600, 2 * 3600, 2 * 3600))
        assertEquals(90 * 60, debt)
        val need = sleepNeed(listOf(8 * 3600, 8 * 3600, 8 * 3600), strain21 = 10.5, shortfalls = listOf(3600))
        assertEquals(8 * 3600 + 1800 + 1800, need.totalSeconds)
        assertEquals(50, sleepNeedPercent(need.totalSeconds / 2, need.totalSeconds))
    }

    @Test
    fun consistencyIsFullWhenBedAndWakeDoNotMove() {
        val beds = listOf(660, 660, 660)
        val wakes = listOf(1080, 1080, 1080)
        assertEquals(100, sleepConsistencyPercent(beds, wakes))
        assertNull(sleepConsistencyPercent(listOf(660, 670), listOf(1080, 1090)))
        val shifted = sleepConsistencyPercent(listOf(660, 720, 780), wakes)!!
        assertTrue(shifted in 1..99)
    }

    @Test
    fun stagesAreEstimatedOnlyWhenANightHasEnoughHeartRate() {
        val start = 1_700_000_000L
        val short = (0 until 10).map { second(start + it, 50) }
        val missing = estimateSleepStages(short, start, start + 600, 48)
        assertFalse(missing.available)
        assertTrue(missing.note.contains("not available"))
        val night = (0 until 40 * 60).map { offset ->
            val hr = when {
                offset < 10 * 60 -> 46
                offset < 20 * 60 -> 51
                offset < 30 * 60 -> 54
                else -> if (offset % 2 == 0) 56 else 60
            }
            second(start + offset.toLong(), hr)
        }
        val estimate = estimateSleepStages(night, start, start + night.size - 1L, 48)
        assertTrue(estimate.available)
        assertTrue(estimate.note.contains("estimated"))
        assertTrue(estimate.minutes.contains(SleepStage.DEEP))
        assertTrue(estimate.minutes.contains(SleepStage.LIGHT))
        assertTrue(estimate.minutes.contains(SleepStage.REM))
    }

    @Test
    fun wakeEventsIgnoreTheFinalWakeUp() {
        val start = 1_000L
        val records = ArrayList<SecondRecord>()
        repeat(60) { records += second(start + it, 48) }
        repeat(40) { records += second(start + 60 + it, 70, motion = 0) }
        repeat(60) { records += second(start + 100 + it, 48) }
        repeat(40) { records += second(start + 160 + it, 70, motion = 0) }
        assertEquals(1, wakeEventCount(records, start, start + records.size))
        assertEquals(60, sleepEfficiencyPercent(120, records.size.toLong()))
    }

    @Test
    fun aLowerRestingHeartRateIsAnImprovementAndTheBandIsMeanPlusOrMinusOneSd() {
        val end = LocalDate.of(2026, 5, 16)
        val dated = (0 until 14).map { offset ->
            val date = end.minusDays((13 - offset).toLong())
            DatedValue(date, if (offset < 7) 50.0 else 40.0)
        }
        val week = rangedSeries(dated, end, ChartRange.WEEK, MetricDirection.LOWER_BETTER)
        assertEquals(7, week.points.size)
        assertEquals("Sat\n16", week.points.last().label)
        assertTrue(week.points.last().highlighted)
        assertEquals(40.0, week.average!!, 0.001)
        assertEquals(-20.0, week.changePercent!!, 0.001)
        assertEquals(ChangeTone.IMPROVED, changeTone(week.changePercent, week.direction))
        assertEquals(ChangeTone.WORSENED, changeTone(10.0, MetricDirection.LOWER_BETTER))
        assertEquals(ChangeTone.NEUTRAL, changeTone(10.0, MetricDirection.NEUTRAL))
        assertFalse(week.line)
        val band = typicalBand(listOf(10.0, 20.0))
        val mean = 15.0
        val sd = abs(10.0 - mean)
        assertEquals(mean - sd, band!!.first, 0.001)
        assertEquals(mean + sd, band.second, 0.001)
        assertNull(typicalBand(listOf(10.0)))
    }

    @Test
    fun aMonthLabelsEachPointWithThatWeeksMonday() {
        val end = LocalDate.of(2026, 5, 16)
        val dated = (0 until 28).map { offset ->
            DatedValue(end.minusDays((27 - offset).toLong()), 70.0 + offset)
        }
        val month = rangedSeries(dated, end, ChartRange.MONTH, MetricDirection.HIGHER_BETTER)
        assertTrue(month.points.size >= 4)
        assertTrue(month.points.all { it.label.matches(Regex("\\d{1,2}/\\d{1,2}")) })
        month.points.forEach { point ->
            val monday = LocalDate.parse(
                "2026/" + point.label,
                java.time.format.DateTimeFormatter.ofPattern("yyyy/M/d"),
            )
            assertEquals(java.time.DayOfWeek.MONDAY, monday.dayOfWeek)
        }
        val half = rangedSeries(dated, end, ChartRange.SIX_MONTH, MetricDirection.HIGHER_BETTER)
        assertTrue(half.line)
        assertTrue(half.points.size == 28)
    }

    private fun second(unix: Long, hr: Int, motion: Int = DayRules.SLEEP) = SecondRecord(
        unixSeconds = unix,
        recordIndex = unix,
        heartRateBpm = hr,
        rrMillis = emptyList(),
        activityClass = null,
        bandMotion = motion,
        stepCounter = 0,
    )
}
