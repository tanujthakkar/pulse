package app.pulse.data

/**
 * Provenance stamped on every row copied from an Edge backup.
 *
 * Strap samples stay in [R18SampleRow]. Imported rows stay in the `edge_*`
 * tables. A shared timestamp is not a merge: [PulseDatabase.heartRateAt]
 * returns the strap row when one exists.
 */
const val EDGE_IMPORT_SOURCE: String = "edge_import"

/** The Edge backup `user_version` this importer knows how to read. */
const val EDGE_BACKUP_USER_VERSION: Int = 58

/** Lookup label for a heart-rate second that came from a strap sync, not an import. */
const val STRAP_SAMPLE_SOURCE: String = "strap"

/** [EdgeOneHzRow.recordKind] for a row copied from `decoded_onehz`. */
const val EDGE_RECORD_ONE_HZ: String = "decoded_onehz"

/**
 * [EdgeOneHzRow.recordKind] for a `samples` row whose `(device_id, ts_ms)` is
 * absent from `decoded_onehz`. Sensor columns stay null. The `samples` table
 * is not copied when `decoded_onehz` already has that second.
 */
const val EDGE_RECORD_SAMPLES: String = "samples"

/** A backup Pulse will not import. The message is safe to show in the UI. */
class EdgeBackupException(message: String) : Exception(message)

/** One stage of a commit, in the order the transaction writes them. */
enum class EdgeImportStep {
    ONE_HZ,
    RR,
    WORKOUTS,
    SLEEP,
    SCORES,
    ARCHIVES,
}

/** Counts and endpoints for one backup table, before any Pulse write. */
data class EdgeCategoryPreview(
    val name: String,
    val rows: Long,
    val startEpochMs: Long?,
    val endEpochMs: Long?,
    val detail: String?,
)

/** Read-only description of a backup. Produced without writing to Pulse or to the file. */
data class EdgePreview(
    val userVersion: Int,
    val sha256: String,
    val byteSize: Long,
    val categories: List<EdgeCategoryPreview>,
    val rangeStartEpochMs: Long?,
    val rangeEndEpochMs: Long?,
    val warnings: List<String>,
)

/** Rows written, skipped, or left untouched for one category. */
data class EdgeCategoryResult(
    val name: String,
    val seen: Int,
    val inserted: Int,
    val alreadyPresent: Int,
    val skippedStrapOverlap: Int,
)

/**
 * Outcome of one committed import.
 *
 * [batchId] is stored on every inserted row. A second commit of the same keys
 * inserts nothing and reports those rows as already present. [archivesDecoded]
 * is always 0: substrate blobs are stored as they appear in the file.
 */
data class EdgeImportResult(
    val batchId: String,
    val sha256: String,
    val categories: List<EdgeCategoryResult>,
    val archivesStored: Int,
    val archivesDeclaredOneHz: Long,
    val archivesDeclaredRr: Long,
    val archivesDecoded: Int,
)

/** Heart rate chosen for one Unix second. [source] is [STRAP_SAMPLE_SOURCE] or [EDGE_IMPORT_SOURCE]. */
data class HeartRateAt(
    val unixSeconds: Long,
    val bpm: Long?,
    val source: String,
)

/** Names used by the preview, the summary, and tests. */
object EdgeCategory {
    const val ONE_HZ: String = "One-second heart rate"
    const val SAMPLES_ONLY: String = "Heart-rate seconds only in samples"
    const val RR: String = "RR intervals"
    const val WORKOUTS: String = "Workouts"
    const val POINTS: String = "GPS points"
    const val SPLITS: String = "Splits"
    const val SETS: String = "Strength sets"
    const val SLEEP: String = "Sleep sessions"
    const val SCORES: String = "Daily scores"
    const val METRICS: String = "Metric values"
    const val METHODS: String = "Metric methods"
    const val VERSIONS: String = "Metric versions"
    const val WAKE: String = "Wake-day features"
    const val ARCHIVES: String = "Substrate archives"
}
