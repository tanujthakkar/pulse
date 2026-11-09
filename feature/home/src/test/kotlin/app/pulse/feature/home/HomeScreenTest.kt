package app.pulse.feature.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.pulse.metrics.DayResult
import app.pulse.metrics.Recovery
import app.pulse.metrics.Rhythm
import app.pulse.metrics.SleepEpisode
import app.pulse.metrics.Strain
import app.pulse.metrics.approximateStrain21
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w480dp-h1400dp")
class HomeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aFullDayShowsTheThreeDialsAndTheStats() {
        var previous = 0
        val content = homeContent(
            day = sampleDay(recovery = 72.0, baselineDays = 4),
            date = DATE,
            canGoPrevious = true,
            canGoNext = false,
            live = sampleLive(),
        )
        compose.setContent {
            HomeScreen(content, onPrevious = { previous += 1 }, onNext = {})
        }
        shows("home_date", "Sunday, Nov 8")
        shows("home_source", "mixed")
        shows("home_recovery", "72")
        val strain = String.format(Locale.US, "%.1f", approximateStrain21(180.0))
        shows("home_strain", strain)
        shows("home_strain", "Approximate")
        shows("home_sleep", "7h 30m")
        shows("home_sleep", "8 h reference")
        shows("home_hrv", "42 ms")
        shows("home_rhr", "48 bpm")
        shows("home_steps", "8,660")
        shows("home_steps", "Strap 7,660")
        shows("home_live", "Live heart rate is unavailable.")
        shows("home_live", "Last sync: none stored.")
        shows("home_live", "Battery: 81%")
        shows("home_live", "Not connected")
        shows("home_sample", "Sample data")
        compose.onNodeWithTag("home_prev").assertIsEnabled().performClick()
        compose.onNodeWithTag("home_next").assertIsNotEnabled()
        org.junit.Assert.assertEquals(1, previous)
    }

    @Test
    fun recoverySaysWhenTheBaselineIsShort() {
        val content = homeContent(
            day = sampleDay(recovery = null, baselineDays = 1),
            date = DATE,
            canGoPrevious = false,
            canGoNext = false,
            live = sampleLive(),
        )
        compose.setContent { HomeScreen(content, onPrevious = {}, onNext = {}) }
        shows("home_recovery", "Recovery needs 3 nights.")
        shows("home_recovery", "—")
        shows("home_strain", "Approximate")
    }

    @Test
    fun anEmptyDaySaysThereAreNoSeconds() {
        val content = homeContent(
            day = null,
            date = DATE,
            canGoPrevious = false,
            canGoNext = false,
            live = sampleLive(battery = null),
        )
        compose.setContent { HomeScreen(content, onPrevious = {}, onNext = {}) }
        shows("home_source", "no data")
        shows("home_recovery", "No strap or Edge seconds")
        shows("home_strain", "No strap or Edge seconds")
        shows("home_sleep", "No strap or Edge seconds")
        shows("home_hrv", "HRV needs a main sleep.")
        shows("home_steps", "No steps stored")
        shows("home_live", "Battery: not stored.")
    }

    private fun shows(tag: String, text: String) {
        compose.onNodeWithTag(tag).assertTextContains(text, substring = true)
    }

    private fun sampleLive(battery: Int? = 81) = HomeLive(
        bpm = null,
        batteryPercent = battery,
        lastSyncUnixSeconds = null,
        zone = ZoneOffset.UTC,
        sampleBanner = "Sample data — not a stored day.",
    )

    private fun sampleDay(recovery: Double?, baselineDays: Int): DayResult {
        val main = SleepEpisode(
            startUnix = 1_700_000_000L,
            endUnix = 1_700_000_000L + 7 * 3600 + 30 * 60,
            sleepSeconds = 7 * 3600 + 30 * 60,
            spanSeconds = (7 * 3600 + 30 * 60).toLong(),
            endDate = DATE,
        )
        return DayResult(
            date = DATE,
            daySeconds = 86_400,
            storedSeconds = 20_000,
            heartSeconds = 18_000,
            anyRrSeconds = 1_000,
            acceptedRrSeconds = 900,
            spanStart = main.startUnix,
            spanEnd = main.endUnix,
            missingInsideSpan = 0,
            gapsAtLeastOneMinute = 0,
            largestGapSeconds = 0,
            sleepSeconds = main.sleepSeconds,
            shortEpisodes = 0,
            shortSleepSeconds = 0,
            episodes = listOf(main),
            main = main,
            rhythm = Rhythm(
                intervals = 800,
                accepted = 700,
                pairs = 400,
                rmssdMs = 42.2,
                heartSamples = 500,
                restingHr = 48,
                meanHr = 52.0,
            ),
            recovery = Recovery(
                score = recovery,
                baselineDays = baselineDays,
                baselineRmssd = if (recovery == null) null else 40.0,
                baselineRestingHr = if (recovery == null) null else 50.0,
                lnRatio = null,
                restingDelta = null,
                unclamped = recovery,
            ),
            strain = Strain(
                trimp = 180.0,
                seconds = 8_000,
                restingHr = 48,
                below100 = 100,
                from100 = 50,
                from120 = 20,
                from140 = 10,
                from160 = 0,
            ),
            stillSeconds = 1,
            walkSeconds = 1,
            runSeconds = 0,
            otherActivitySeconds = 0,
            stepIncrease = 7_660,
            edgeStepIncrease = 1_000,
            stepWraps = 0,
            stepResets = 0,
            stepsAcrossLongGaps = 0,
            strapSeconds = 12_000,
            edgeSeconds = 8_000,
            strapHeartSeconds = 11_000,
            edgeHeartSeconds = 7_000,
            strapAcceptedRrSeconds = 500,
            edgeAcceptedRrSeconds = 400,
            sleepDetection = "band-motion",
            edgeNote = null,
        )
    }
}

private val DATE: LocalDate = LocalDate.of(2026, 11, 8)

