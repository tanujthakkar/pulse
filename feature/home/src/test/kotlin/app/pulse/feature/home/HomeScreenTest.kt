package app.pulse.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.pulse.link.LinkPhase
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
import org.junit.Assert.assertFalse
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
        val picks = ArrayList<LocalDate>()
        var health = 0
        var opened = 0
        val content = homeContent(
            day = sampleDay(recovery = 72.0, baselineDays = 4),
            date = DATE,
            today = DATE,
            logged = emptyList(),
            live = sampleLive(),
        )
        assertEquals(DialTone.YELLOW, content.recovery.tone)
        assertEquals("94%", content.sleep.value)
        compose.setContent {
            host(
                content,
                onPickDate = { picks += it },
                onOpenHealth = { health += 1 },
                onOpenActivity = { opened += 1 },
            )
        }
        compose.onNodeWithTag("home_header").assertTextContains("SUN, NOV 8")
        val dials = compose.onNodeWithTag("home_dials").onChildren()
        dials[0].assertTextContains("SLEEP")
        dials[0].assertTextContains("94%")
        dials[1].assertTextContains("RECOVERY")
        dials[1].assertTextContains("72%")
        dials[2].assertTextContains("STRAIN")
        val strain = String.format(Locale.US, "%.1f", approximateStrain21(180.0))
        dials[2].assertTextContains(strain)
        shows("dash_hrv", "42")
        shows("dash_rhr", "48")
        shows("dash_steps", "8,660")
        shows("dash_sleep_debt", "0:30")
        shows("dash_sleep_debt", "8h reference")
        assertEquals(0, compose.onAllNodesWithTag("dash_avg_hr").fetchSemanticsNodes().size)
        shows("activity_$SLEEP_START", "SLEEP")
        shows("activity_$SLEEP_START", "7:30")
        compose.onNodeWithTag("home_next").assertIsNotEnabled()
        compose.onNodeWithTag("home_prev").performClick()
        assertEquals(listOf(DATE.minusDays(1)), picks)
        compose.onNodeWithTag("home_sleep").performClick()
        compose.onNodeWithTag("home_recovery").performClick()
        assertEquals(1, opened)
        compose.onNodeWithTag("home_health").performClick()
        assertEquals(1, health)
        assertFalse(content.monitor.spo2.available)
        assertFalse(content.monitor.respiratory.available)
        assertFalse(content.monitor.skin.available)
        assertTrue(content.monitor.hrv.available)
        assertTrue(content.monitor.rhr.available)
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
        shows("home_recovery", "--")
        shows("home_recovery", "RECOVERY")
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
        val picks = ArrayList<LocalDate>()
        compose.setContent { host(content, onPickDate = { picks += it }) }
        shows("home_header", "SUN, NOV 8")
        shows("home_recovery", "--")
        shows("dash_empty", "No stored history")
        shows("activity_empty", "No activities")
        compose.onNodeWithTag("home_prev").performClick()
        compose.onNodeWithTag("home_next").performClick()
        assertEquals(listOf(DATE.minusDays(1), DATE.plusDays(1)), picks)
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

    @Test
    fun healthMonitorUsesComputedNightValuesAndLeavesSpo2Empty() {
        val prior = sampleDay(recovery = 70.0, baselineDays = 4).copy(
            date = DATE.minusDays(1),
            sleepSkinTempC = 33.2,
        )
        val day = sampleDay(recovery = 72.0, baselineDays = 4).copy(
            breathsPerMinute = 15.2,
            sleepSkinTempC = 33.5,
            meanHeartBpm = 72.0,
        )
        val content = homeContent(
            day = day,
            date = DATE,
            today = DATE,
            logged = emptyList(),
            live = sampleLive().copy(bpm = 64, phase = LinkPhase.LINKED, trace = listOf(HeartPoint(0L, 60), HeartPoint(1000L, 64))),
            history = listOf(prior),
        )
        assertEquals("64", content.monitor.bpmText)
        assertEquals("Device connected", content.monitor.status)
        assertEquals("15.2", content.monitor.respiratory.text)
        assertTrue(content.monitor.respiratory.available)
        assertFalse(content.monitor.spo2.available)
        assertEquals("--", content.monitor.spo2.text)
        assertEquals("48", content.monitor.rhr.text)
        assertEquals("42", content.monitor.hrv.text)
        assertEquals("+0.3", content.monitor.skin.text)
        assertEquals("72", content.dashboard.first { it.tag == "dash_avg_hr" }.value)
        compose.setContent { HealthMonitorScreen(content.monitor, onBack = {}) }
        shows("health_hr", "64")
        shows("health_status", "Device connected")
        shows("health_rr", "15.2")
        shows("health_spo2", "No data")
        shows("health_spo2", "--")
        shows("health_rhr", "48")
        shows("health_hrv", "42")
        shows("health_temp", "+0.3")
    }

    @Composable
    private fun host(
        content: HomeContent,
        onPickDate: (LocalDate) -> Unit = {},
        onOpenHealth: () -> Unit = {},
        onOpenActivity: (app.pulse.metrics.ActivityCard) -> Unit = {},
    ) {
        HomeScreen(
            content,
            onPickDate = onPickDate,
            onOpenActivity = onOpenActivity,
            onOpenHealth = onOpenHealth,
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
