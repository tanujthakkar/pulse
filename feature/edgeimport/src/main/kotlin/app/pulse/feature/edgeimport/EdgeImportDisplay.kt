package app.pulse.feature.edgeimport

import app.pulse.data.EDGE_IMPORT_SOURCE
import app.pulse.data.EdgeCategoryPreview
import app.pulse.data.EdgeCategoryResult
import app.pulse.data.EdgeImportResult
import app.pulse.data.EdgePreview
import java.time.Instant

/** Screen copy for a backup preview. The importer stays free of these sentences. */
fun importPreviewText(preview: EdgePreview): String {
    val lines = mutableListOf<String>()
    lines += "Edge backup user_version ${preview.userVersion}, ${preview.byteSize} bytes."
    lines += "SHA-256 ${preview.sha256}."
    preview.warnings.forEach { lines += it }
    val rangeStart = preview.rangeStartEpochMs
    val rangeEnd = preview.rangeEndEpochMs
    lines += if (rangeStart != null && rangeEnd != null) {
        "Range ${formatEpoch(rangeStart)} to ${formatEpoch(rangeEnd)}."
    } else {
        "Range has no epoch timestamps."
    }
    preview.categories.forEach { category -> lines += categoryLines(category) }
    return lines.joinToString(separator = "\n")
}

/** Screen copy after a commit. Every inserted row is labeled [EDGE_IMPORT_SOURCE]. */
fun importSummaryText(result: EdgeImportResult): String {
    val lines = mutableListOf<String>()
    lines += "Imported as $EDGE_IMPORT_SOURCE."
    lines += "Batch ${result.batchId}."
    lines += "SHA-256 ${result.sha256}."
    result.categories.forEach { category -> lines += resultLine(category) }
    lines += "Substrate archives stored raw: ${result.archivesStored}. Decoded: ${result.archivesDecoded}."
    lines += "Declared one-Hz rows left undecoded: ${result.archivesDeclaredOneHz}."
    lines += "Declared RR rows left undecoded: ${result.archivesDeclaredRr}."
    return lines.joinToString(separator = "\n")
}

fun importProgressText(name: String, written: Int, total: Int): String = "$name ($written of $total)."

private fun categoryLines(category: EdgeCategoryPreview): String {
    val lines = mutableListOf("${category.name}: ${category.rows}.")
    val start = category.startEpochMs
    val end = category.endEpochMs
    if (start != null && end != null) {
        lines += "From ${formatEpoch(start)} to ${formatEpoch(end)}."
    }
    category.detail?.let { lines += it }
    return lines.joinToString(separator = "\n")
}

private fun resultLine(category: EdgeCategoryResult): String =
    "${category.name}: ${category.inserted} inserted, ${category.alreadyPresent} already present, " +
        "${category.skippedStrapOverlap} skipped where strap data covers that second."

private fun formatEpoch(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()
