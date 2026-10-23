package app.pulse.feature.sync

import app.pulse.sync.HistoryLimits
import app.pulse.sync.PhoneTime
import app.pulse.sync.SessionOutcome
import app.pulse.sync.SyncActivity
import app.pulse.sync.SyncPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDisplayTest {
    @Test
    fun idleShowsZerosAndNoError() {
        val ui = syncUi(SyncActivity())
        assertEquals("Idle", ui.state)
        assertEquals("0", ui.chunks)
        assertEquals("0", ui.records)
        assertEquals("—", ui.outcome)
        assertEquals("—", ui.time)
        assertFalse(ui.outcomeIsError)
        assertNull(ui.notice)
        assertTrue(ui.canSync)
    }

    @Test
    fun aRunningAttemptShowsLiveCountsAndKeepsTheLastOutcome() {
        val ui = syncUi(
            SyncActivity(
                phase = SyncPhase.STREAM,
                running = true,
                chunks = 2,
                records = 5,
                outcome = SessionOutcome.DISCONNECTED,
                finishedAt = PhoneTime(1_700_000_000L, 7),
            ),
        )
        assertEquals("Receiving history", ui.state)
        assertEquals("2", ui.chunks)
        assertEquals("5", ui.records)
        assertEquals(outcomeMessage(SessionOutcome.DISCONNECTED), ui.outcome)
        assertFalse(ui.outcomeIsError)
        assertEquals("1700000000 s, 7 ticks", ui.time)
        assertFalse(ui.canSync)
    }

    @Test
    fun everyPhaseHasALabel() {
        val labels = SyncPhase.entries.map(::phaseLabel)
        assertEquals(SyncPhase.entries.size, labels.toSet().size)
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals("Reading clock", phaseLabel(SyncPhase.CLOCK))
        assertEquals("Setting clock", phaseLabel(SyncPhase.CLOCK_SET))
        assertEquals("Checking clock", phaseLabel(SyncPhase.CLOCK_VERIFY))
        assertEquals("Reading data range", phaseLabel(SyncPhase.RANGE))
        assertEquals("Starting history", phaseLabel(SyncPhase.START))
        assertEquals("Finished", phaseLabel(SyncPhase.DONE))
    }

    @Test
    fun everyOutcomeHasItsOwnMessage() {
        val messages = SessionOutcome.entries.map(::outcomeMessage)
        assertEquals(SessionOutcome.entries.size, messages.toSet().size)
        assertTrue(messages.all { it.isNotBlank() })
        assertTrue(outcomeMessage(SessionOutcome.WATCHDOG).contains("${HistoryLimits.WATCHDOG_MS / 1000}"))
        assertTrue(
            outcomeMessage(SessionOutcome.BOUNDED).contains("${HistoryLimits.MAX_RECORDS_WITHOUT_END}"),
        )
        SessionOutcome.entries.forEach { outcome ->
            val ui = syncUi(
                SyncActivity(
                    phase = SyncPhase.DONE,
                    chunks = 1,
                    records = 4,
                    outcome = outcome,
                    finishedAt = PhoneTime(42L, 0),
                ),
            )
            assertEquals(outcomeMessage(outcome), ui.outcome)
            assertEquals("42 s, 0 ticks", ui.time)
            assertEquals(outcome != SessionOutcome.COMPLETE, ui.outcomeIsError)
            assertTrue(ui.canSync)
        }
    }

    @Test
    fun aMissingLinkIsANoticeAndDoesNotClearTheLastOutcome() {
        val ui = syncUi(
            SyncActivity(
                outcome = SessionOutcome.ABORTED,
                finishedAt = PhoneTime(9L, 1),
                blocked = "Link a strap before syncing.",
            ),
        )
        assertEquals("Link a strap before syncing.", ui.notice)
        assertTrue(ui.outcomeIsError)
        assertEquals(outcomeMessage(SessionOutcome.ABORTED), ui.outcome)
        assertTrue(ui.canSync)
    }
}
