package app.pulse.data

import java.io.File
import java.util.UUID

/**
 * Copies Edge history into Pulse.
 *
 * [preview] only reads the file. [commit] writes Pulse inside one Room
 * transaction. A thrown exception, including [afterCategory], rolls that
 * transaction back. Rows already stored for the same natural key are left
 * as they were. A one-Hz or RR row whose Unix second already has a layout-18
 * sample is not inserted. Substrate archive blobs are copied unchanged and
 * marked undecoded.
 *
 * The caller passes a cache copy. This class never opens the user's document.
 */
class EdgeImporter(
    private val database: PulseDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val afterCategory: (EdgeImportStep) -> Unit = {},
) {
    fun preview(file: File): EdgePreview {
        EdgeBackup.open(file).use { backup ->
            return backup.preview(edgeFileSha256(file), file.length())
        }
    }

    fun commit(file: File, progress: (String, Int, Int) -> Unit = { _, _, _ -> }): EdgeImportResult {
        val sha256 = edgeFileSha256(file)
        val byteSize = file.length()
        EdgeBackup.open(file).use { backup ->
            return database.runInTransaction<EdgeImportResult> {
                write(backup, sha256, byteSize, progress)
            }
        }
    }

    private fun write(
        backup: EdgeBackup,
        sha256: String,
        byteSize: Long,
        progress: (String, Int, Int) -> Unit,
    ): EdgeImportResult {
        val batchId = newId()
        val strapSeconds = database.edgeImport().strapSeconds().toHashSet()
        val results = mutableListOf<EdgeCategoryResult>()

        val oneHzTotal = (backup.count("decoded_onehz") + backup.countSamplesOnly()).toInt()
        results += writeRows(
            name = EdgeCategory.ONE_HZ,
            total = oneHzTotal,
            progress = progress,
            read = { sink -> backup.readOneHz(batchId, sink) },
            overlap = { row: EdgeOneHzRow -> row.tsMs / 1000 in strapSeconds },
            insert = database.edgeImport()::insertOneHz,
            count = database.edgeImport()::oneHzCount,
        )
        afterCategory(EdgeImportStep.ONE_HZ)

        val rrTotal = backup.count("decoded_rr").toInt()
        results += writeRows(
            name = EdgeCategory.RR,
            total = rrTotal,
            progress = progress,
            read = { sink -> backup.readRr(batchId, sink) },
            overlap = { row: EdgeRrRow -> (row.beatTsMs ?: row.rrTsMs) / 1000 in strapSeconds },
            insert = database.edgeImport()::insertRr,
            count = database.edgeImport()::rrCount,
        )
        afterCategory(EdgeImportStep.RR)

        results += writeRows(
            name = EdgeCategory.WORKOUTS,
            total = backup.count("sessions").toInt(),
            progress = progress,
            read = { sink -> backup.readWorkouts(batchId, sink) },
            overlap = { _: EdgeWorkoutRow -> false },
            insert = database.edgeImport()::insertWorkouts,
            count = database.edgeImport()::workoutCount,
        )
        results += writeRows(
            name = EdgeCategory.POINTS,
            total = backup.count("workout_route").toInt(),
            progress = progress,
            read = { sink -> backup.readPoints(batchId, sink) },
            overlap = { _: EdgeWorkoutPointRow -> false },
            insert = database.edgeImport()::insertPoints,
            count = database.edgeImport()::pointCount,
        )
        results += writeRows(
            name = EdgeCategory.SPLITS,
            total = backup.count("workout_split").toInt(),
            progress = progress,
            read = { sink -> backup.readSplits(batchId, sink) },
            overlap = { _: EdgeWorkoutSplitRow -> false },
            insert = database.edgeImport()::insertSplits,
            count = database.edgeImport()::splitCount,
        )
        results += writeRows(
            name = EdgeCategory.SETS,
            total = backup.count("strength_set").toInt(),
            progress = progress,
            read = { sink -> backup.readSets(batchId, sink) },
            overlap = { _: EdgeStrengthSetRow -> false },
            insert = database.edgeImport()::insertSets,
            count = database.edgeImport()::setCount,
        )
        afterCategory(EdgeImportStep.WORKOUTS)

        results += writeRows(
            name = EdgeCategory.SLEEP,
            total = backup.count("sleep_session_candidates").toInt(),
            progress = progress,
            read = { sink -> backup.readSleep(batchId, sink) },
            overlap = { _: EdgeSleepRow -> false },
            insert = database.edgeImport()::insertSleep,
            count = database.edgeImport()::sleepCount,
        )
        afterCategory(EdgeImportStep.SLEEP)

        results += writeRows(
            name = EdgeCategory.SCORES,
            total = backup.count("day_result").toInt(),
            progress = progress,
            read = { sink -> backup.readScores(batchId, sink) },
            overlap = { _: EdgeDayScoreRow -> false },
            insert = database.edgeImport()::insertScores,
            count = database.edgeImport()::scoreCount,
        )
        results += writeRows(
            name = EdgeCategory.METRICS,
            total = backup.count("metric_series").toInt(),
            progress = progress,
            read = { sink -> backup.readMetrics(batchId, sink) },
            overlap = { _: EdgeMetricSeriesRow -> false },
            insert = database.edgeImport()::insertMetrics,
            count = database.edgeImport()::metricCount,
        )
        results += writeRows(
            name = EdgeCategory.METHODS,
            total = backup.count("metric_method").toInt(),
            progress = progress,
            read = { sink -> backup.readMethods(batchId, sink) },
            overlap = { _: EdgeMetricMethodRow -> false },
            insert = database.edgeImport()::insertMethods,
            count = database.edgeImport()::methodCount,
        )
        results += writeRows(
            name = EdgeCategory.VERSIONS,
            total = backup.count("metric_series_version").toInt(),
            progress = progress,
            read = { sink -> backup.readVersions(batchId, sink) },
            overlap = { _: EdgeMetricVersionRow -> false },
            insert = database.edgeImport()::insertVersions,
            count = database.edgeImport()::versionCount,
        )
        results += writeRows(
            name = EdgeCategory.WAKE,
            total = backup.count("wake_day_features").toInt(),
            progress = progress,
            read = { sink -> backup.readWake(batchId, sink) },
            overlap = { _: EdgeWakeFeaturesRow -> false },
            insert = database.edgeImport()::insertWake,
            count = database.edgeImport()::wakeCount,
        )
        afterCategory(EdgeImportStep.SCORES)

        val declared = backup.declaredArchiveRows()
        val archives = writeRows(
            name = EdgeCategory.ARCHIVES,
            total = backup.count("substrate_archive").toInt(),
            progress = progress,
            read = { sink -> backup.readArchives(batchId, sink) },
            overlap = { _: EdgeSubstrateArchiveRow -> false },
            insert = database.edgeImport()::insertArchives,
            count = database.edgeImport()::archiveCount,
        )
        results += archives
        afterCategory(EdgeImportStep.ARCHIVES)

        val inserted = results.sumOf { it.inserted }
        val present = results.sumOf { it.alreadyPresent }
        val overlap = results.sumOf { it.skippedStrapOverlap }
        database.edgeImport().insertBatch(
            EdgeImportBatchRow(
                id = batchId,
                createdAtMs = clock(),
                source = EDGE_IMPORT_SOURCE,
                userVersion = EDGE_BACKUP_USER_VERSION,
                byteSize = byteSize,
                sha256 = sha256,
                insertedRows = inserted,
                alreadyPresent = present,
                skippedStrapOverlap = overlap,
            ),
        )
        return EdgeImportResult(
            batchId = batchId,
            sha256 = sha256,
            categories = results,
            archivesStored = archives.inserted,
            archivesDeclaredOneHz = declared.first,
            archivesDeclaredRr = declared.second,
            archivesDecoded = 0,
        )
    }

    private fun <T> writeRows(
        name: String,
        total: Int,
        progress: (String, Int, Int) -> Unit,
        read: (sink: (List<T>) -> Unit) -> Unit,
        overlap: (T) -> Boolean,
        insert: (List<T>) -> List<Long>,
        count: () -> Int,
    ): EdgeCategoryResult {
        var seen = 0
        var inserted = 0
        var present = 0
        var skipped = 0
        read { batch ->
            val keep = ArrayList<T>(batch.size)
            batch.forEach { row ->
                seen += 1
                if (overlap(row)) skipped += 1 else keep += row
            }
            if (keep.isNotEmpty()) {
                val before = count()
                insert(keep)
                val added = count() - before
                inserted += added
                present += keep.size - added
            }
            progress(name, seen, total)
        }
        if (seen == 0) progress(name, 0, total)
        return EdgeCategoryResult(name, seen, inserted, present, skipped)
    }
}
