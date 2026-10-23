package app.pulse.feature.sync

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.pulse.link.StrapCommandPort
import app.pulse.sync.ChunkReceipt
import app.pulse.sync.PhoneTime
import app.pulse.sync.R18Draft
import app.pulse.sync.RawFrameDraft
import app.pulse.sync.SessionOutcome
import app.pulse.sync.SyncActivity
import app.pulse.sync.SyncPhase
import app.pulse.sync.SyncStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncRouteTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun syncNowWithoutALinkShowsTheNoticeAndLeavesTheButtonEnabled() {
        val viewModel = SyncViewModel(UnusedSyncStore(), StrapCommandPort())
        compose.setContent { SyncRoute(viewModel) }
        compose.onNodeWithTag("sync_state").assertTextEquals("Idle")
        compose.onNodeWithTag("sync_chunks").assertTextEquals("0")
        compose.onNodeWithTag("sync_records").assertTextEquals("0")
        compose.onNodeWithTag("sync_outcome").assertTextEquals("—")
        compose.onNodeWithTag("sync_time").assertTextEquals("—")
        compose.onNodeWithTag("sync_now").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Link a strap before syncing.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("sync_notice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("sync_now").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("sync_busy").assertDoesNotExist()
        assertEquals("0:0", viewModel.modulePins)
    }

    @Test
    fun aRunningSyncDisablesTheButtonAndShowsLiveCounts() {
        var clicks = 0
        compose.setContent {
            SyncScreen(
                ui = syncUi(
                    SyncActivity(
                        phase = SyncPhase.STREAM,
                        running = true,
                        chunks = 3,
                        records = 9,
                    ),
                ),
                onSyncNow = { clicks += 1 },
            )
        }
        compose.onNodeWithTag("sync_state").assertTextEquals("Receiving history")
        compose.onNodeWithTag("sync_chunks").assertTextEquals("3")
        compose.onNodeWithTag("sync_records").assertTextEquals("9")
        compose.onNodeWithTag("sync_busy").assertIsDisplayed()
        compose.onNodeWithTag("sync_now").assertIsNotEnabled()
        compose.onNodeWithTag("sync_error").assertDoesNotExist()
        assertEquals(0, clicks)
    }

    @Test
    fun eachFailedOutcomeShowsItsErrorAndCompleteDoesNot() {
        val outcome = mutableStateOf(SessionOutcome.COMPLETE)
        var clicks = 0
        compose.setContent {
            SyncScreen(
                ui = syncUi(
                    SyncActivity(
                        phase = SyncPhase.DONE,
                        chunks = 1,
                        records = 2,
                        outcome = outcome.value,
                        finishedAt = PhoneTime(1_700_000_000L, 0),
                    ),
                ),
                onSyncNow = { clicks += 1 },
            )
        }
        SessionOutcome.entries.forEach { current ->
            clicks = 0
            compose.runOnIdle { outcome.value = current }
            compose.onNodeWithTag("sync_state").assertTextEquals("Finished")
            compose.onNodeWithTag("sync_chunks").assertTextEquals("1")
            compose.onNodeWithTag("sync_records").assertTextEquals("2")
            compose.onNodeWithTag("sync_time").performScrollTo().assertTextEquals("1700000000 s, 0 ticks")
            compose.onNodeWithTag("sync_now").performScrollTo().assertIsEnabled().performClick()
            assertEquals(current.name, 1, clicks)
            if (current == SessionOutcome.COMPLETE) {
                compose.onNodeWithTag("sync_error").assertDoesNotExist()
                compose.onNodeWithTag("sync_outcome").performScrollTo().assertTextEquals("History finished.")
            } else {
                compose.onNodeWithTag("sync_error").performScrollTo().assertTextEquals(outcomeMessage(current))
                compose.onNodeWithTag("sync_outcome").assertDoesNotExist()
            }
        }
    }
}

private class UnusedSyncStore : SyncStore {
    override fun openSession(
        deviceAddress: String,
        startedUnixSeconds: Long,
        startedSubsecondTicks: Int?,
        rangeSnapshot: ByteArray,
    ): Long = error("unused")

    override fun commitChunk(
        sessionId: Long,
        frames: List<RawFrameDraft>,
        r18Rows: List<R18Draft>,
        archived: List<RawFrameDraft>,
        token: ByteArray,
    ): ChunkReceipt = error("unused")

    override fun closeSession(
        sessionId: Long,
        endedUnixSeconds: Long,
        endedSubsecondTicks: Int?,
    ) = error("unused")

    override fun markAcknowledged(
        sessionId: Long,
        token: ByteArray,
        ackedUnixSeconds: Long,
    ) = error("unused")
}
