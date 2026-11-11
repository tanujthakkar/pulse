package app.pulse.metrics

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.math.ln

class MetricsTest {
    @Test
    fun dayMetricsAreImplementedAsApproximateFormulas() {
        assertTrue(Metrics.IMPLEMENTED)
        val book = computeDays(emptyList(), ZoneOffset.UTC)
        assertTrue(dayBookText(book).contains("Not WHOOP scores"))
        assertTrue(dayBookText(book).contains("No layout-18 or Edge one-second rows"))
    }

    @Test
    fun coverageGapsAndTheLaterDuplicate() {
        val day = LocalDate.of(2026, 1, 2).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val records = listOf(
            second(day, 1, hr = null),
            second(day, 2, hr = 60),
            second(day + 1, 1, hr = 61, rr = listOf(1000)),
            second(day + 2, 1, hr = 62),
            second(day + 100, 1, hr = 70, activity = 1, steps = 10),
        )
        val result = computeDays(records, ZoneOffset.UTC).days.single()
        assertEquals(4, result.storedSeconds)
        assertEquals(4, result.heartSeconds)
        assertEquals(1, result.acceptedRrSeconds)
        assertEquals(1, result.anyRrSeconds)
        assertEquals(97L, result.missingInsideSpan)
        assertEquals(1, result.gapsAtLeastOneMinute)
        assertEquals(97L, result.largestGapSeconds)
        assertEquals(1, result.walkSeconds)
        assertEquals(3, result.otherActivitySeconds)
        assertTrue(dayBookText(computeDays(records, ZoneOffset.UTC)).contains("HR coverage 4 / 4"))
    }

    @Test
    fun fiveMinutesBridgesAndTheNextSecondSplits() {
        val start = LocalDate.of(2026, 1, 5).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val bridged = listOf(
            second(start, 1, hr = 50, motion = DayRules.SLEEP),
            second(start + 299, 1, hr = 50, motion = DayRules.SLEEP),
        )
        val split = listOf(
            second(start, 1, hr = 50, motion = DayRules.SLEEP),
            second(start + 300, 1, hr = 50, motion = DayRules.SLEEP),
        )
        assertEquals(1, computeDays(bridged, ZoneOffset.UTC).days.single().shortEpisodes)
        assertEquals(2, computeDays(split, ZoneOffset.UTC).days.single().shortEpisodes)
    }

    @Test
    fun overnightSleepBelongsToTheMorningItEnds() {
        val zone = ZoneOffset.UTC
        val evening = LocalDate.of(2026, 5, 1).atTime(23, 0).toEpochSecond(zone)
        val book = computeDays(sleepRun(evening, 3 * 3600), zone)
        val first = book.days.first { it.date == LocalDate.of(2026, 5, 1) }
        val second = book.days.first { it.date == LocalDate.of(2026, 5, 2) }
        assertNull(first.main)
        assertTrue(first.episodes.isEmpty())
        assertEquals(3600, first.sleepSeconds)
        assertEquals(10_800, second.main!!.sleepSeconds)
        assertEquals(1, second.episodes.size)
        assertEquals(10_800, second.episodes.single().sleepSeconds)
        assertEquals(LocalDate.of(2026, 5, 2), second.main.endDate)
    }

    @Test
    fun aNewYorkSpringForwardDayHas82800Seconds() {
        val zone = ZoneId.of("America/New_York")
        val start = LocalDate.of(2026, 3, 8).atStartOfDay(zone).toEpochSecond()
        val day = computeDays(listOf(second(start, 1, hr = 60)), zone).days.single()
        assertEquals(LocalDate.of(2026, 3, 8), day.date)
        assertEquals(82_800L, day.daySeconds)
    }

    @Test
    fun anEvenBaselineAveragesTheTwoMiddleDays() {
        val zone = ZoneOffset.UTC
        val first = LocalDate.of(2026, 6, 1)
        val records = (0..4).flatMap { offset ->
            val start = first.plusDays(offset.toLong()).atStartOfDay(zone).toEpochSecond() + 3600
            val high = offset == 1 || offset == 2
            sleepRun(start, 1200, hr = 50) { index ->
                val other = if (high) 1200 else 1100
                if (index % 2 == 0) 1000 else other
            }
        }
        val last = computeDays(records, zone).days.first { it.date == first.plusDays(4) }
        assertEquals(4, last.recovery!!.baselineDays)
        assertEquals(150.0, last.recovery.baselineRmssd!!, 0.001)
        val expected = 50.0 + 40.0 * ln(100.0 / 150.0)
        assertEquals(expected, last.recovery.score!!, 0.001)
    }

    @Test
    fun aShortWakeInsideSleepStaysOneEpisodeAndALongGapSplits() {
        val start = LocalDate.of(2026, 1, 3).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val bridged = sleepRun(start, 1200) +
            listOf(second(start + 1200, 1, hr = 70, motion = 0)) +
            sleepRun(start + 1300, 100)
        val book = computeDays(bridged, ZoneOffset.UTC)
        assertEquals(1, book.days.single().episodes.size)
        assertEquals(1300, book.days.single().main!!.sleepSeconds)

        val split = sleepRun(start, 1200) + sleepRun(start + 1600, 100)
        val splitDay = computeDays(split, ZoneOffset.UTC).days.single()
        assertEquals(1, splitDay.episodes.size)
        assertEquals(1, splitDay.shortEpisodes)
        assertEquals(100, splitDay.shortSleepSeconds)
        assertEquals(1200, splitDay.main!!.sleepSeconds)
    }

    @Test
    fun rmssdUsesAcceptedPairsAndDropsALargeJump() {
        val start = LocalDate.of(2026, 1, 4).atStartOfDay(ZoneOffset.UTC).toEpochSecond() + 3600
        val steady = sleepRun(start, 1200)
        val jumped = steady.mapIndexed { index, sample ->
            if (index == 10) sample.copy(rrMillis = listOf(700)) else sample
        }
        val rhythm = computeDays(jumped, ZoneOffset.UTC).days.single().rhythm!!
        assertEquals(1200, rhythm.intervals)
        assertEquals(1200, rhythm.accepted)
        assertEquals(1197, rhythm.pairs)
        assertEquals(0.0, rhythm.rmssdMs!!, 0.001)
        assertEquals(50, rhythm.restingHr)
    }

    @Test
    fun recoveryIsBlankUntilThreeEarlierDaysThenFollowsTheFormula() {
        val zone = ZoneOffset.UTC
        val first = LocalDate.of(2026, 2, 1)
        val days = (0..3).flatMap { offset ->
            val start = first.plusDays(offset.toLong()).atStartOfDay(zone).toEpochSecond() + 3600
            sleepRun(start, 1200, hr = 50) { index ->
                if (offset < 3) {
                    if (index % 2 == 0) 1000 else 1100
                } else {
                    if (index % 2 == 0) 1000 else 1200
                }
            }
        }
        val book = computeDays(days, zone)
        val early = book.days.first { it.date == first }
        assertNull(early.recovery!!.score)
        assertEquals(0, early.recovery.baselineDays)
        val last = book.days.first { it.date == first.plusDays(3) }
        assertEquals(3, last.recovery!!.baselineDays)
        val expected = (50.0 + 40.0 * ln(2.0)).coerceIn(0.0, 100.0)
        assertEquals(expected, last.recovery.score!!, 0.001)
        assertEquals(100.0, last.recovery.baselineRmssd!!, 0.001)
        val text = dayBookText(book)
        assertTrue(text.contains("Approximate"))
        assertTrue(text.contains("not light, deep, or REM"))
    }

    @Test
    fun banisterTrimpUsesTheAssumedMaxAndTheSleepRestingRate() {
        val start = LocalDate.of(2026, 3, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val sleep = sleepRun(start + 3600, 1200, hr = 50)
        val work = (0 until 60).map { index ->
            second(start + 20_000 + index, 1, hr = 120, motion = 0, activity = 2, steps = 100 + index)
        }
        val day = computeDays(sleep + work, ZoneOffset.UTC).days.single()
        val perSecond = banisterSecond(120, 50, 190)
        val sleepSecond = banisterSecond(50, 50, 190)
        val expected = sleepSecond * 1200 + perSecond * 60
        assertEquals(expected, day.strain!!.trimp!!, 1e-6)
        assertEquals(0.5 * 0.64 * exp(1.92 * 0.5) / 60.0, perSecond, 1e-9)
        assertEquals(60, day.runSeconds)
        assertEquals(159L, day.stepIncrease)
        assertEquals(0, day.stepWraps)
    }

    @Test
    fun theStepCounterWrapsAndALargeDropIsAReset() {
        val start = LocalDate.of(2026, 4, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val records = listOf(
            second(start, 1, hr = 60, steps = 65_530),
            second(start + 1, 1, hr = 60, steps = 20),
            second(start + 2, 1, hr = 60, steps = 5_000),
            second(start + 3, 1, hr = 60, steps = 100),
        )
        val day = computeDays(records, ZoneOffset.UTC).days.single()
        assertEquals(26L + 4_980L, day.stepIncrease)
        assertEquals(1, day.stepWraps)
        assertEquals(1, day.stepResets)
    }

    @Test
    fun anEdgeNightWithBandMotionProducesSleepRmssdAndRestingHr() {
        val start = LocalDate.of(2026, 7, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() + 3600
        val records = sleepRun(start, 1200, source = SOURCE_EDGE) { index ->
            if (index % 2 == 0) 1000 else 1100
        }
        val day = computeDays(records, ZoneOffset.UTC).days.single()
        assertEquals("Edge import", sourceName(day))
        assertEquals("band-motion", day.sleepDetection)
        assertEquals(false, day.main!!.edgeProvided)
        assertEquals(100.0, day.rhythm!!.rmssdMs!!, 0.001)
        assertEquals(50, day.rhythm.restingHr)
        assertEquals(1200, day.edgeSeconds)
        assertEquals(0, day.strapSeconds)
        val text = dayBookText(computeDays(records, ZoneOffset.UTC))
        assertTrue(text.contains("Source: Edge import"))
        assertTrue(text.contains("strap second wins"))
    }

    @Test
    fun anEdgeOnsetLabelsSleepWhenBandMotionIsAbsent() {
        val start = LocalDate.of(2026, 7, 2).atStartOfDay(ZoneOffset.UTC).toEpochSecond() + 3600
        val records = sleepRun(start, 1200, source = SOURCE_EDGE, motion = null) { index ->
            if (index % 2 == 0) 1000 else 900
        }
        val window = EdgeSleepWindow("2026-07-02", 2, start, start + 1200)
        val day = computeDays(records, ZoneOffset.UTC, listOf(window)).days.single()
        assertEquals(true, day.main!!.edgeProvided)
        assertEquals("Edge onset/offset", day.sleepDetection)
        assertEquals(100.0, day.rhythm!!.rmssdMs!!, 0.001)
        assertEquals(50, day.rhythm.restingHr)
        val text = dayBookText(computeDays(records, ZoneOffset.UTC, listOf(window)))
        assertTrue(text.contains("Main sleep (Edge-provided onset/offset)"))
    }

    @Test
    fun bandMotionWinsOverAnEdgeWindow() {
        val start = LocalDate.of(2026, 7, 3).atStartOfDay(ZoneOffset.UTC).toEpochSecond() + 3600
        val records = sleepRun(start, 1200, source = SOURCE_EDGE)
        val window = EdgeSleepWindow("ignored", 1, start, start + 1200)
        val day = computeDays(records, ZoneOffset.UTC, listOf(window)).days.single()
        assertEquals(false, day.main!!.edgeProvided)
        assertEquals("band-motion", day.sleepDetection)
    }

    @Test
    fun threeEarlierEdgeNightsUnlockRecoveryAndAnEdgeScoreStaysSeparate() {
        val zone = ZoneOffset.UTC
        val first = LocalDate.of(2026, 8, 1)
        val records = (0..3).flatMap { offset ->
            val start = first.plusDays(offset.toLong()).atStartOfDay(zone).toEpochSecond() + 3600
            sleepRun(start, 1200, source = SOURCE_EDGE) { index ->
                val other = if (offset == 3) 1200 else 1100
                if (index % 2 == 0) 1000 else other
            }
        }
        val lastDate = first.plusDays(3)
        val note = EdgeDayNote(lastDate.toString(), 4, 40.0, 10.0, 80.0)
        val book = computeDays(records, zone, edgeScores = listOf(note))
        val last = book.days.first { it.date == lastDate }
        assertEquals(3, last.recovery!!.baselineDays)
        val expected = 50.0 + 40.0 * ln(2.0)
        assertEquals(expected, last.recovery.score!!, 0.001)
        assertEquals(80.0, last.edgeNote!!.readiness)
        val text = dayBookText(book)
        assertTrue(text.contains("Edge day_result (not used for Pulse recovery or strain)"))
        assertTrue(text.contains("readiness 80.0000"))
        assertTrue(text.contains("Recovery: ${"%.4f".format(java.util.Locale.US, expected)}"))
    }

    @Test
    fun edgeSecondsBesideStrapAreNotCountedTwice() {
        val zone = ZoneOffset.UTC
        val night = LocalDate.of(2026, 11, 8).atStartOfDay(zone).toEpochSecond() + 3600
        val day = night + 11 * 3600
        val strapNight = sleepRun(night, 1200, source = SOURCE_STRAP)
        val strapDay = (0 until 600).map { index ->
            second(day + index, 1, hr = 140, motion = 0, activity = 1, steps = index, source = SOURCE_STRAP)
        }
        val edgeShifted = (0 until 600).map { index ->
            second(
                day + 1 + index,
                0,
                hr = 140,
                motion = 0,
                activity = 1,
                steps = index * 3,
                source = SOURCE_EDGE,
            )
        }
        val strapOnly = computeDays(strapNight + strapDay, zone).days.single()
        val mixed = computeDays(strapNight + strapDay + edgeShifted, zone).days.single()
        val strapStrain = strapOnly.strain!!
        val mixedStrain = mixed.strain!!
        assertEquals(strapStrain.seconds, mixedStrain.seconds)
        assertEquals(strapStrain.trimp!!, mixedStrain.trimp!!, 0.001)
        assertEquals(strapOnly.stepIncrease, mixed.stepIncrease)
        assertEquals(0L, mixed.edgeStepIncrease)
        assertEquals(599L, mixed.stepIncrease)
        assertTrue(mixedStrain.trimp!! < strapStrain.trimp!! * 1.05 + 1.0)
    }

    @Test
    fun stepDeltasDoNotJumpAcrossTheOtherSource() {
        val start = LocalDate.of(2026, 11, 8).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val edgeBefore = second(start, 1, hr = 70, steps = 100, source = SOURCE_EDGE, motion = 0)
        val edgeAfter = second(start + 100, 1, hr = 70, steps = 900, source = SOURCE_EDGE, motion = 0)
        val strap = listOf(
            second(start + 40, 1, hr = 100, steps = 0, source = SOURCE_STRAP, motion = 0),
            second(start + 41, 2, hr = 100, steps = 25, source = SOURCE_STRAP, motion = 0),
        )
        val day = computeDays(listOf(edgeBefore, edgeAfter) + strap, ZoneOffset.UTC).days.single()
        assertEquals(25L, day.stepIncrease)
        assertEquals(0L, day.edgeStepIncrease)
        assertEquals(2, day.strapSeconds)
        assertEquals(2, day.edgeSeconds)
    }

    @Test
    fun aDayWithNoSecondsIsLabeledNoData() {
        val note = EdgeDayNote("2026-11-03", 1, 40.0, 30.0, 10.0)
        val day = computeDays(emptyList(), ZoneOffset.UTC, edgeScores = listOf(note)).days.single()
        assertEquals(0, day.strapSeconds)
        assertEquals(0, day.edgeSeconds)
        assertTrue(dayBookText(computeDays(emptyList(), ZoneOffset.UTC, edgeScores = listOf(note))).contains("Source: no data"))
    }

    @Test
    fun strainDialApproaches21WithoutReachingItOnAModerateDay() {
        assertEquals(0.0, approximateStrain21(0.0), 0.0001)
        val moderate = approximateStrain21(DayRules.STRAIN_DIAL_TRIMP)
        assertEquals(21.0 * (1.0 - kotlin.math.exp(-1.0)), moderate, 0.0001)
        assertTrue(moderate in 13.0..14.0)
        assertTrue(approximateStrain21(10_000.0) <= 21.0)
        assertTrue(approximateStrain21(10_000.0) > moderate)
    }

    private fun sourceName(day: DayResult): String = when {
        day.strapSeconds > 0 && day.edgeSeconds > 0 -> "mixed"
        day.edgeSeconds > 0 -> "Edge import"
        else -> "strap"
    }

    private fun sleepRun(
        start: Long,
        seconds: Int,
        hr: Int = 50,
        source: String = SOURCE_STRAP,
        motion: Int? = DayRules.SLEEP,
        rr: (Int) -> Int = { 1000 },
    ): List<SecondRecord> = (0 until seconds).map { index ->
        second(
            start + index,
            1,
            hr = hr,
            rr = listOf(rr(index)),
            motion = motion,
            activity = 0,
            source = source,
        )
    }

    private fun second(
        unix: Long,
        index: Long,
        hr: Int?,
        rr: List<Int> = emptyList(),
        motion: Int? = 0,
        activity: Int? = null,
        steps: Int = 0,
        source: String = SOURCE_STRAP,
    ): SecondRecord = SecondRecord(
        unixSeconds = unix,
        recordIndex = index,
        heartRateBpm = hr,
        rrMillis = rr,
        activityClass = activity,
        bandMotion = motion,
        stepCounter = steps,
        source = source,
    )
}
