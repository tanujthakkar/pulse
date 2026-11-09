package app.pulse.feature.sync

import app.pulse.sync.AckKind
import app.pulse.sync.HistoryAckFirmware
import app.pulse.sync.HistoryLimits
import app.pulse.sync.PhoneTime
import app.pulse.sync.SessionOutcome
import app.pulse.sync.SyncActivity
import app.pulse.sync.SyncPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDisplayTest {
    @Test
    fun idleShowsZerosAndNoError() {
        val activity = SyncActivity()
        val ui = syncUi(activity)
        assertEquals("Idle", ui.state)
        assertEquals(chunkLine(activity), ui.chunks)
        assertEquals("0", ui.records)
        assertEquals("0", ui.savedRecords)
        assertEquals("0", ui.savedChunks)
        assertEquals("—", ui.outcome)
        assertEquals("—", ui.time)
        assertFalse(ui.outcomeIsError)
        assertNull(ui.notice)
        assertTrue(ui.canSync)
        assertTrue(ui.historyAckEnabled)
        assertTrue(ui.ackWarning.contains("reclaim"))
        assertTrue(ui.ackWarning.contains(HistoryAckFirmware.ALLOWED))
        assertTrue(ui.ackStatus.contains("Strap result 1: 0"))
        assertTrue(ui.ackStatus.contains("GATT status 0"))
        assertTrue(ui.ackStatus.contains("not a strap result"))
        val off = syncUi(SyncActivity(historyAckEnabled = false))
        assertTrue(off.ackWarning.contains("does not send command 23"))
        assertTrue(off.ackStatus.contains("History acknowledgement is off"))
    }

    @Test
    fun handoffAndResultsAreNotDescribedAsRadioSuccess() {
        val handed = syncUi(
            SyncActivity(
                phase = SyncPhase.STREAM,
                running = true,
                ackSent = 1,
                lastAckKind = AckKind.HANDED_OFF,
            ),
        )
        assertTrue(handed.ackStatus.contains("passed to the radio"))
        assertTrue(handed.ackStatus.contains("GATT status 0"))
        assertTrue(handed.ackStatus.contains("not a strap result"))
        assertFalse(handed.canSync)
        val accepted = syncUi(
            SyncActivity(
                lastAckKind = AckKind.RESULT,
                lastAckResult = 1,
                ackResult1 = 1,
                ackEligibleRecords = 35,
            ),
        )
        assertTrue(accepted.ackStatus.contains("result was 1"))
        assertTrue(accepted.ackStatus.contains("does not prove"))
        assertTrue(accepted.ackStatus.contains("35"))
        val failed = syncUi(SyncActivity(ackResult0 = 1, lastAckKind = AckKind.RESULT, lastAckResult = 0))
        assertTrue(failed.ackStatus.contains("was not updated"))
        val pending = syncUi(SyncActivity(ackPending = 1))
        assertTrue(pending.ackStatus.contains("Pending"))
        assertTrue(pending.ackStatus.contains("not success"))
        val none = syncUi(
            SyncActivity(
                phase = SyncPhase.DONE,
                ackUnanswered = 1,
                lastAckKind = AckKind.NO_RESPONSE,
                outcome = SessionOutcome.WATCHDOG,
            ),
        )
        assertTrue(none.ackStatus.contains("No strap result"))
        assertNotEquals(none.outcome, none.ackStatus)
        val refused = syncUi(SyncActivity(ackRadioRefused = 1, lastAckKind = AckKind.RADIO_REFUSED))
        assertTrue(refused.ackStatus.contains("refused"))
        assertTrue(refused.ackStatus.contains("Not retried"))
    }

    @Test
    fun aRunningAttemptHidesThePreviousOutcome() {
        val activity = SyncActivity(
            phase = SyncPhase.STREAM,
            running = true,
            chunks = 1,
            records = 5,
            endsReceived = 5,
            endsRetried = 4,
            savedSamples = 20,
            savedChunks = 3,
            outcome = SessionOutcome.DISCONNECTED,
            finishedAt = PhoneTime(1_700_000_000L, 7),
        )
        val ui = syncUi(activity)
        assertEquals("Receiving history", ui.state)
        assertEquals(chunkLine(activity), ui.chunks)
        assertEquals("5", ui.records)
        assertEquals("20", ui.savedRecords)
        assertEquals("3", ui.savedChunks)
        assertEquals("—", ui.outcome)
        assertFalse(ui.outcomeIsError)
        assertEquals("—", ui.time)
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
            assertEquals(outcomeMessage(outcome, committedChunks = 1), ui.outcome)
            assertEquals("42 s, 0 ticks", ui.time)
            assertEquals(outcome != SessionOutcome.COMPLETE, ui.outcomeIsError)
            assertTrue(ui.canSync)
        }
    }

    @Test
    fun aTimeoutNamesSavedRowsAndTheUncommittedTailSeparately() {
        val kept = outcomeMessage(
            SessionOutcome.WATCHDOG,
            committedChunks = 1,
            droppedUncommitted = false,
        )
        assertTrue(kept.contains("60"))
        assertTrue(kept.contains("stay stored"))
        assertTrue(kept.contains("No uncommitted records were dropped."))
        assertFalse(kept.contains("open chunk was dropped"))

        val tail = outcomeMessage(
            SessionOutcome.WATCHDOG,
            committedChunks = 1,
            droppedUncommitted = true,
        )
        assertTrue(tail.contains("stay stored"))
        assertTrue(tail.contains("The uncommitted records were dropped."))

        val empty = outcomeMessage(
            SessionOutcome.WATCHDOG,
            committedChunks = 0,
            droppedUncommitted = true,
        )
        assertTrue(empty.contains("Nothing from this attempt was saved."))
        assertTrue(empty.contains("The uncommitted records were dropped."))
        val finished = outcomeMessage(SessionOutcome.COMPLETE, committedChunks = 0, droppedUncommitted = true)
        assertTrue(finished.contains("History finished."))
        assertTrue(finished.contains("uncommitted records were not saved"))
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
