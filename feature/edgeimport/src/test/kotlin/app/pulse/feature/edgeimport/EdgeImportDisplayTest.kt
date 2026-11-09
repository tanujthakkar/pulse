package app.pulse.feature.edgeimport

import app.pulse.data.EDGE_IMPORT_SOURCE
import app.pulse.data.EdgeCategory
import app.pulse.data.EdgeCategoryPreview
import app.pulse.data.EdgeCategoryResult
import app.pulse.data.EdgeImportResult
import app.pulse.data.EdgePreview
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeImportDisplayTest {
    @Test
    fun previewListsCountsRangeAndUndecodedArchives() {
        val text = importPreviewText(preview())
        assertTrue(text.contains("user_version 58"))
        assertTrue(text.contains("SHA-256 abc"))
        assertTrue(text.contains("One-second heart rate: 2."))
        assertTrue(text.contains("Heart-rate seconds only in samples: 1."))
        assertTrue(text.contains("RR intervals: 4."))
        assertTrue(text.contains("Workouts: 2."))
        assertTrue(text.contains("GPS points: 3."))
        assertTrue(text.contains("Sleep sessions: 2."))
        assertTrue(text.contains("Days 2026-10-20 to 2026-11-08."))
        assertTrue(text.contains("Substrate archives: 1."))
        assertTrue(text.contains("not decoded"))
        assertTrue(text.contains("Declared one-Hz rows: 100."))
        assertTrue(text.contains("Declared RR rows: 40."))
        val start = Instant.ofEpochMilli(1_762_556_800_000)
        val end = Instant.ofEpochMilli(1_763_668_800_000)
        assertTrue(text.contains("Range $start to $end."))
        assertFalse(text.contains("OSA1 layout bytes"))
    }

    @Test
    fun summaryNamesTheBatchAndLeavesArchivesUndecoded() {
        val text = importSummaryText(
            EdgeImportResult(
                batchId = "batch-9",
                sha256 = "abc",
                categories = listOf(
                    EdgeCategoryResult(EdgeCategory.ONE_HZ, seen = 2, inserted = 1, alreadyPresent = 0, skippedStrapOverlap = 1),
                ),
                archivesStored = 1,
                archivesDeclaredOneHz = 100,
                archivesDeclaredRr = 40,
                archivesDecoded = 0,
            ),
        )
        assertTrue(text.contains("Imported as $EDGE_IMPORT_SOURCE."))
        assertTrue(text.contains("Batch batch-9."))
        assertTrue(text.contains("1 inserted, 0 already present, 1 skipped where strap data covers that second."))
        assertTrue(text.contains("Substrate archives stored raw: 1. Decoded: 0."))
        assertTrue(text.contains("Declared one-Hz rows left undecoded: 100."))
        assertTrue(text.contains("Declared RR rows left undecoded: 40."))
    }

    private fun preview(): EdgePreview = EdgePreview(
        userVersion = 58,
        sha256 = "abc",
        byteSize = 1200,
        categories = listOf(
            EdgeCategoryPreview(EdgeCategory.ONE_HZ, 2, 1_762_000_000_000, 1_763_000_000_000, null),
            EdgeCategoryPreview(
                EdgeCategory.SAMPLES_ONLY,
                1,
                null,
                null,
                "Imported only when decoded_onehz has no row for that device and timestamp.",
            ),
            EdgeCategoryPreview(EdgeCategory.RR, 4, null, null, null),
            EdgeCategoryPreview(EdgeCategory.WORKOUTS, 2, null, null, null),
            EdgeCategoryPreview(EdgeCategory.POINTS, 3, null, null, null),
            EdgeCategoryPreview(EdgeCategory.SLEEP, 2, null, null, "Days 2026-10-20 to 2026-11-08."),
            EdgeCategoryPreview(
                EdgeCategory.ARCHIVES,
                1,
                1_762_556_800_000,
                1_763_668_800_000,
                "Stored raw and not decoded. Declared one-Hz rows: 100. Declared RR rows: 40. Codec 1.",
            ),
        ),
        rangeStartEpochMs = 1_762_556_800_000,
        rangeEndEpochMs = 1_763_668_800_000,
        warnings = emptyList(),
    )
}
