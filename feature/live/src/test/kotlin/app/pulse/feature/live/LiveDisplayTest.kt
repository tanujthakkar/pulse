package app.pulse.feature.live

import app.pulse.ble.LiveHeartRate
import app.pulse.ble.SensorContact
import app.pulse.link.LinkPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveDisplayTest {
    @Test
    fun inRangeBpmAndRrAreShown() {
        val ui = liveUi(
            phase = LinkPhase.LINKED,
            sample = sample(bpm = 72, rr = listOf(812, 790)),
            trace = listOf(70, 72),
        )
        assertEquals("Connected.", ui.status)
        assertEquals("72", ui.bpm)
        assertEquals("812 ms, 790 ms", ui.rr)
        assertEquals("Skin contact", ui.contact)
        assertEquals(listOf(70, 72), ui.sparkline)
    }

    @Test
    fun bpmOutsideTheDisplayWindowIsBlankAndRrStays() {
        val ui = liveUi(
            phase = LinkPhase.BONDING,
            sample = sample(bpm = 10, rr = listOf(1000), contact = SensorContact.NOT_DETECTED),
            trace = emptyList(),
        )
        assertEquals("Bonding. Heart rate can show before that finishes.", ui.status)
        assertEquals(LIVE_BPM_PLACEHOLDER, ui.bpm)
        assertEquals("1000 ms", ui.rr)
        assertEquals("No skin contact", ui.contact)
        assertNull(displayedBpm(24))
        assertEquals(25, displayedBpm(25))
        assertEquals(230, displayedBpm(230))
        assertNull(displayedBpm(231))
    }

    @Test
    fun unsupportedContactIsHiddenAndEmptyRrIsAPlaceholder() {
        val ui = liveUi(
            phase = LinkPhase.CONNECTING,
            sample = sample(bpm = 60, rr = emptyList(), contact = SensorContact.UNSUPPORTED),
            trace = emptyList(),
        )
        assertEquals("60", ui.bpm)
        assertEquals(LIVE_BPM_PLACEHOLDER, ui.rr)
        assertNull(ui.contact)
    }

    @Test
    fun idleWithoutASampleShowsThePlaceholder() {
        val ui = liveUi(LinkPhase.IDLE, sample = null, trace = emptyList())
        assertEquals("Open Connect and start a search.", ui.status)
        assertEquals(LIVE_BPM_PLACEHOLDER, ui.bpm)
        assertEquals(LIVE_BPM_PLACEHOLDER, ui.rr)
        assertNull(ui.contact)
    }

    @Test
    fun traceKeepsTheLatestReadingsAndSkipsAHiddenBpm() {
        var trace = emptyList<Int>()
        repeat(LIVE_TRACE_LENGTH + 2) { index ->
            trace = appendTrace(trace, index + 40)
        }
        assertEquals(LIVE_TRACE_LENGTH, trace.size)
        assertEquals(42, trace.first())
        assertEquals(40 + LIVE_TRACE_LENGTH + 1, trace.last())
        assertEquals(trace, appendTrace(trace, displayedBpm(10)))
    }

    private fun sample(
        bpm: Int,
        rr: List<Int>,
        contact: SensorContact = SensorContact.DETECTED,
    ) = LiveHeartRate(tsMillis = 1L, bpm = bpm, rrMs = rr, contact = contact)
}
