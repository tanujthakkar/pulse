package app.pulse.feature.edgeimport

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.pulse.data.EdgeCategory
import app.pulse.data.EdgeCategoryPreview
import app.pulse.data.EdgePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EdgeImportScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun previewOffersImportAndSummaryReplacesIt() {
        var commits = 0
        var picks = 0
        compose.setContent {
            EdgeImportScreen(
                state = ImportUiState.Ready(samplePreview()),
                onPick = { picks += 1 },
                onCommit = { commits += 1 },
                onBack = {},
            )
        }
        compose.onNodeWithTag("import_body").assertTextContains("One-second heart rate: 2.", substring = true)
        compose.onNodeWithTag("import_body").assertTextContains("not decoded", substring = true)
        compose.onNodeWithTag("import_commit").assertIsDisplayed().performClick()
        compose.onNodeWithTag("import_pick").performClick()
        assertEquals(1, commits)
        assertEquals(1, picks)
    }

    @Test
    fun runningShowsProgressAndHidesTheCommitButton() {
        compose.setContent {
            EdgeImportScreen(
                state = ImportUiState.Running("One-second heart rate (1 of 4)."),
                onPick = {},
                onCommit = {},
                onBack = {},
            )
        }
        compose.onNodeWithTag("import_body").assertTextContains("One-second heart rate (1 of 4).", substring = true)
        compose.onNodeWithTag("import_busy").assertIsDisplayed()
        compose.onNodeWithTag("import_commit").assertDoesNotExist()
    }

    @Test
    fun finishedSummaryStaysOnScreen() {
        compose.setContent {
            EdgeImportScreen(
                state = ImportUiState.Finished("Imported as edge_import.\nDecoded: 0."),
                onPick = {},
                onCommit = {},
                onBack = {},
            )
        }
        compose.onNodeWithTag("import_body").assertTextContains("Imported as edge_import.", substring = true)
        compose.onNodeWithTag("import_body").assertTextContains("Decoded: 0.", substring = true)
        assertTrue(importFailureMessage(IllegalStateException("boom")) == "The backup could not be imported.")
    }

    private fun samplePreview(): EdgePreview = EdgePreview(
        userVersion = 58,
        sha256 = "abc",
        byteSize = 10,
        categories = listOf(
            EdgeCategoryPreview(EdgeCategory.ONE_HZ, 2, null, null, null),
            EdgeCategoryPreview(
                EdgeCategory.ARCHIVES,
                1,
                null,
                null,
                "Stored raw and not decoded. Declared one-Hz rows: 1. Declared RR rows: 1.",
            ),
        ),
        rangeStartEpochMs = null,
        rangeEndEpochMs = null,
        warnings = emptyList(),
    )
}
