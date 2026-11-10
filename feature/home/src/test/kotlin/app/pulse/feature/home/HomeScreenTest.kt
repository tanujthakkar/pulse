package app.pulse.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onChildren
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w480dp-h1400dp")
class HomeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun aFullDayShowsSleepThenRecoveryThenStrain() {
        var settings = 0
        val content = homeContent(
            day = sampleDay(recovery = 72.0, baselineDays = 4),
            date = DATE,
            today = DATE,
            logged = emptyList(),
            live = sampleLive(),
        )
        compose.setContent {
            HomeScreen(
                content,
                onOpenSettings = { settings += 1 },
                onPickDate = {},
                onBackToToday = {},
                onOpenActivity = {},
                onSaveActivity = { _, _, _, _ -> },
                onDeleteActivity = {},
            )
        }
        compose.onNodeWithTag("home_header").assertTextContains("Today")
        shows("home_source", "mixed")
        val dials = compose.onNodeWithTag("home_dials").onChildren()
        dials[0].assertTextContains("Sleep")
        dials[0].assertTextContains("7h 30m")
        dials[1].assertTextContains("Recovery")
        dials[1].assertTextContains("72")
        dials[2].assertTextContains("Strain")
        val strain = String.format(Locale.US, "%.1f", approximateStrain21(180.0))
        dials[2].assertTextContains(strain)
        shows("home_hrv", "42 ms")
        shows("home_rhr", "48 bpm")
        shows("home_sleep_hours", "7h 30m")
        shows("home_steps", "8,660")
        shows("home_live", "— · Not connected")
        shows("home_battery", "81%")
        shows("home_sync", "—")
        shows("activity_$SLEEP_START", "Sleep")
        compose.onNodeWithTag("home_settings").performClick()
        assertEquals(1, settings)
    }

    @Test
    fun aShortBaselineLeavesTheRecoveryDialBlank() {
        val content = homeContent(
            day = sampleDay(recovery = null, baselineDays = 1),
            date = DATE,
            today = DATE,
            logged = emptyList(),
            live = sampleLive(),
        )
        compose.setContent { host(content) }
        shows("home_recovery", "—")
        shows("home_recovery", "Recovery")
        shows("home_strain", "13.3")
    }

    @Test
    fun anEmptyDayShowsDashes() {
        val content = homeContent(
            day = null,
            date = DATE,
            today = DATE.minusDays(1),
            logged = emptyList(),
            live = sampleLive(battery = null),
        )
        var today = 0
        compose.setContent { host(content, onBackToToday = { today += 1 }) }
        shows("home_source", "no data")
        shows("home_header", "Sun, Nov 8")
        shows("home_recovery", "—")
        shows("home_hrv", "—")
        shows("home_steps", "—")
        shows("home_battery", "—")
        shows("activity_empty", "No activities")
        compose.onNodeWithTag("home_today").performClick()
        assertEquals(1, today)
    }

    @Test
    fun browsingAMonthDoesNotLoadADate() {
        val content = homeContent(
            day = sampleDay(recovery = 72.0, baselineDays = 4),
            date = DATE,
            today = DATE,
            logged = emptyList(),
            live = sampleLive(),
        )
        val picks = ArrayList<LocalDate>()
        compose.setContent { host(content, onPickDate = { picks += it }) }
        compose.onNodeWithTag("home_header").performClick()
        shows("cal_month", "November 2026")
        compose.onNodeWithTag("cal_next").performClick()
        shows("cal_month", "December 2026")
        assertTrue(picks.isEmpty())
        compose.onNodeWithTag("cal_day_3").performClick()
        assertEquals(listOf(LocalDate.of(2026, 12, 3)), picks)
    }

    @Composable
    private fun host(
        content: HomeContent,
        onPickDate: (LocalDate) -> Unit = {},
        onBackToToday: () -> Unit = {},
    ) {
        HomeScreen(
            content,
            onOpenSettings = {},
            onPickDate = onPickDate,
            onBackToToday = onBackToToday,
            onOpenActivity = {},
            onSaveActivity = { _, _, _, _ -> },
            onDeleteActivity = {},
        )
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
            startUnix = SLEEP_START,
            endUnix = SLEEP_START + 7 * 3600 + 30 * 60,
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

private const val SLEEP_START: Long = 1_700_000_000L
private val DATE: LocalDate = LocalDate.of(2026, 11, 8)
