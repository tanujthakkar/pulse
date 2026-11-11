package app.pulse.feature.home

import app.pulse.ble.BondBegin
import app.pulse.ble.DeviceBond
import app.pulse.data.ActivityLog
import app.pulse.data.DayMetricsSource
import app.pulse.data.DaySnapshot
import app.pulse.link.LinkAlarm
import app.pulse.link.LinkClock
import app.pulse.link.LinkPorts
import app.pulse.link.LiveHeartRateFeed
import app.pulse.link.StrapLink
import app.pulse.metrics.DayBook
import app.pulse.metrics.DayResult
import app.pulse.metrics.LoggedActivity
import app.pulse.metrics.Recovery
import app.pulse.metrics.Rhythm
import app.pulse.metrics.SleepEpisode
import app.pulse.metrics.Strain
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home used to keep the empty book it read at startup. A finished sync wrote
 * the rows, and the rings stayed blank until the process was started again.
 */
class HomeRefreshTest {
    @Test
    fun aFinishedSyncUpdatesHomeWithoutANewViewModel() {
        val source = MutableDays()
        val model = HomeViewModel(
            days = source,
            activities = EmptyLog,
            link = StrapLink(QuietPorts, idleClock()),
            heart = LiveHeartRateFeed(),
            clock = { DATE },
            executor = Executor { it.run() },
            debounceMs = 0,
            collectHeart = false,
        )
        assertEquals("--", model.ui.value!!.sleep.value)
        assertTrue(model.ui.value!!.activities.isEmpty())
        assertEquals("--", model.ui.value!!.monitor.hrv.text)

        source.book = DayBook("UTC", listOf(syncedDay()), null)
        source.fire()

        val shown = model.ui.value!!
        assertEquals("88%", shown.sleep.value)
        assertEquals("7h 0m", shown.activities.single().tile)
        assertEquals("49", shown.dashboard.first { it.tag == "dash_hrv" }.value)
        assertEquals("48", shown.dashboard.first { it.tag == "dash_rhr" }.value)
        assertEquals("49", shown.monitor.hrv.text)
        assertEquals("48", shown.monitor.rhr.text)
        assertTrue(shown.recovery.value.endsWith("%"))
    }

    @Test
    fun todayFillsInOnALaterReadWithoutANewViewModel() {
        val today = LocalDate.of(2026, 11, 10)
        val yesterday = today.minusDays(1)
        val source = MutableDays()
        val model = HomeViewModel(
            days = source,
            activities = EmptyLog,
            link = StrapLink(QuietPorts, idleClock()),
            heart = LiveHeartRateFeed(),
            clock = { today },
            executor = Executor { it.run() },
            debounceMs = 0,
            collectHeart = false,
        )
        assertEquals(today, model.ui.value!!.date)
        assertTrue(model.ui.value!!.isToday)
        assertEquals("--", model.ui.value!!.sleep.value)

        source.book = DayBook("UTC", listOf(syncedDay(yesterday)), null)
        source.fire()

        assertEquals(today, model.ui.value!!.date)
        assertEquals("--", model.ui.value!!.sleep.value)
        assertTrue(model.ui.value!!.activities.isEmpty())
        model.pick(yesterday)
        assertEquals("88%", model.ui.value!!.sleep.value)
        assertEquals("7h 0m", model.ui.value!!.activities.single().tile)
        model.pick(today)
        assertEquals("--", model.ui.value!!.sleep.value)

        source.book = DayBook("UTC", listOf(syncedDay(today), syncedDay(yesterday)), null)
        source.fire()

        val shown = model.ui.value!!
        assertEquals(today, shown.date)
        assertTrue(shown.isToday)
        assertEquals("88%", shown.sleep.value)
        assertEquals("7h 0m", shown.activities.single().tile)
        assertEquals("49", shown.monitor.hrv.text)
    }

    private fun syncedDay(date: LocalDate = DATE): DayResult {
        val start = date.minusDays(1).atTime(23, 0).toEpochSecond(ZoneOffset.UTC)
        val asleep = 7 * 3600
        val episode = SleepEpisode(start, start + asleep - 1, asleep, asleep.toLong(), date)
        return DayResult(
            date = date,
            daySeconds = 86_400,
            storedSeconds = asleep,
            heartSeconds = asleep,
            anyRrSeconds = asleep,
            acceptedRrSeconds = asleep,
            spanStart = start,
            spanEnd = episode.endUnix,
            missingInsideSpan = 0,
            gapsAtLeastOneMinute = 0,
            largestGapSeconds = 0,
            sleepSeconds = asleep,
            shortEpisodes = 0,
            shortSleepSeconds = 0,
            episodes = listOf(episode),
            main = episode,
            rhythm = Rhythm(800, 700, 400, 49.0, 500, 48, 52.0),
            recovery = Recovery(72.0, 4, 40.0, 50.0, null, null, 72.0),
            strain = Strain(180.0, 8_000, 48, 100, 50, 20, 10, 0),
            stillSeconds = 1,
            walkSeconds = 1,
            runSeconds = 0,
            otherActivitySeconds = 0,
            stepIncrease = 8_000,
            edgeStepIncrease = 0,
            stepWraps = 0,
            stepResets = 0,
            stepsAcrossLongGaps = 0,
            strapSeconds = asleep,
            edgeSeconds = 0,
            strapHeartSeconds = asleep,
            edgeHeartSeconds = 0,
            strapAcceptedRrSeconds = asleep,
            edgeAcceptedRrSeconds = 0,
            sleepDetection = "band-motion",
            edgeNote = null,
        )
    }

    private class MutableDays : DayMetricsSource {
        var book = DayBook("UTC", emptyList(), "No layout-18 or Edge one-second rows are stored.")
        private var listener: (() -> Unit)? = null

        override fun report(zone: ZoneId): String = ""

        override fun snapshot(zone: ZoneId): DaySnapshot =
            DaySnapshot(book, batteryPercent = 80, lastSyncUnixSeconds = 1_700_000_000)

        override fun watch(onChange: () -> Unit): () -> Unit {
            listener = onChange
            return { listener = null }
        }

        fun fire() {
            listener?.invoke()
        }
    }

    private object EmptyLog : ActivityLog {
        override fun all(): List<LoggedActivity> = emptyList()
        override fun save(id: Long?, type: String, startUnix: Long, endUnix: Long) = Unit
        override fun delete(id: Long) = Unit
    }

    private object QuietPorts : LinkPorts {
        override fun startScan() = Unit
        override fun stopScan() = Unit
        override fun bondOf(address: String): DeviceBond = DeviceBond.ABSENT
        override fun beginBond(address: String): BondBegin = BondBegin.NOT_STARTED
        override fun cancelBond() = Unit
        override fun openSession(address: String) = Unit
        override fun closeSession() = Unit
        override fun watchHeartRate(address: String) = Unit
    }

    private fun idleClock(): LinkClock = object : LinkClock {
        override fun after(delayMs: Long, task: () -> Unit): LinkAlarm = LinkAlarm { }
    }

    private companion object {
        val DATE: LocalDate = LocalDate.of(2026, 11, 9)
    }
}
