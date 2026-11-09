package app.pulse.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import java.security.MessageDigest

/**
 * A read-only view of an Edge SQLite backup.
 *
 * The file is opened with [SQLiteDatabase.OPEN_READONLY]. Callers copy a
 * document into cache first and pass that copy. This class does not write,
 * and it does not decode `substrate_archive` blobs.
 */
internal class EdgeBackup private constructor(
    private val db: SQLiteDatabase,
) : AutoCloseable {
    fun preview(sha256: String, byteSize: Long): EdgePreview {
        requireKnownSchema()
        val categories = listOf(
            countRange(EdgeCategory.ONE_HZ, "decoded_onehz", "ts_ms", "ts_ms", alreadyMillis = true),
            samplesOnlyPreview(),
            countRange(
                EdgeCategory.RR,
                "decoded_rr",
                "COALESCE(beat_ts_ms, rr_ts_ms)",
                "COALESCE(beat_ts_ms, rr_ts_ms)",
                alreadyMillis = true,
            ),
            countRange(EdgeCategory.WORKOUTS, "sessions", "start_ts", "COALESCE(end_ts, start_ts)", alreadyMillis = false),
            countRange(EdgeCategory.POINTS, "workout_route", "ts_ms", "ts_ms", alreadyMillis = true),
            countPlain(EdgeCategory.SPLITS, "workout_split"),
            countRange(EdgeCategory.SETS, "strength_set", "at_ts", "at_ts", alreadyMillis = false),
            dayRange(EdgeCategory.SLEEP, "sleep_session_candidates", "day_id"),
            dayRange(EdgeCategory.SCORES, "day_result", "day_id"),
            dayRange(EdgeCategory.METRICS, "metric_series", "date"),
            dayRange(EdgeCategory.METHODS, "metric_method", "date"),
            dayRange(EdgeCategory.VERSIONS, "metric_series_version", "date"),
            dayRange(EdgeCategory.WAKE, "wake_day_features", "day_id"),
            archivePreview(),
        )
        val epochs = categories.flatMap { listOfNotNull(it.startEpochMs, it.endEpochMs) }
        return EdgePreview(
            userVersion = userVersion(),
            sha256 = sha256,
            byteSize = byteSize,
            categories = categories,
            rangeStartEpochMs = epochs.minOrNull(),
            rangeEndEpochMs = epochs.maxOrNull(),
            warnings = warnings(),
        )
    }

    fun count(table: String): Long {
        require(table in COUNTABLE) { "refusing to count $table" }
        return longQuery("SELECT COUNT(*) FROM $table")
    }

    fun countSamplesOnly(): Long = longQuery(SAMPLES_ONLY_COUNT)

    fun declaredArchiveRows(): Pair<Long, Long> {
        db.rawQuery(
            "SELECT COALESCE(SUM(onehz_rows), 0), COALESCE(SUM(rr_rows), 0) FROM substrate_archive",
            null,
        ).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0) to cursor.getLong(1)
        }
    }

    fun readOneHz(batchId: String, sink: (List<EdgeOneHzRow>) -> Unit) {
        readBatches(ONE_HZ_SQL, sink) { cursor -> cursor.toOneHz(batchId, EDGE_RECORD_ONE_HZ) }
        readBatches(SAMPLES_ONLY_SQL, sink) { cursor -> cursor.toSampleOnly(batchId) }
    }

    fun readRr(batchId: String, sink: (List<EdgeRrRow>) -> Unit) {
        readBatches(RR_SQL, sink) { cursor ->
            EdgeRrRow(
                deviceId = cursor.reqString("device_id"),
                tsMs = cursor.reqLong("ts_ms"),
                recTs = cursor.reqLong("rec_ts"),
                beatIndex = cursor.reqLong("beat_index"),
                rrTsMs = cursor.reqLong("rr_ts_ms"),
                rrMs = cursor.reqLong("rr_ms"),
                deviceFamily = cursor.text("device_family"),
                edgeSource = cursor.text("source"),
                beatTsMs = cursor.long("beat_ts_ms"),
                importBatchId = batchId,
                source = EDGE_IMPORT_SOURCE,
            )
        }
    }

    fun readWorkouts(batchId: String, sink: (List<EdgeWorkoutRow>) -> Unit) {
        readBatches(WORKOUT_SQL, sink) { cursor ->
            EdgeWorkoutRow(
                id = cursor.reqString("id"),
                startTs = cursor.reqLong("start_ts"),
                endTs = cursor.long("end_ts"),
                type = cursor.reqString("type"),
                status = cursor.reqString("status"),
                calories = cursor.real("calories"),
                strain = cursor.real("strain"),
                maxHr = cursor.long("max_hr"),
                durationMin = cursor.long("duration_min"),
                zoneMinJson = cursor.text("zone_min_json"),
                steps = cursor.long("steps"),
                cadenceSpm = cursor.long("cadence_spm"),
                hrrBpm = cursor.real("hrr_bpm"),
                edgeSource = cursor.reqString("source"),
                isPrivate = cursor.reqLong("private"),
                deviceFamily = cursor.text("device_family"),
                traceJson = cursor.text("trace_json"),
                traceSamples = cursor.long("trace_samples"),
                rpe = cursor.real("rpe"),
                createdAt = cursor.reqLong("created_at"),
                avgHr = cursor.long("avg_hr"),
                vo2maxEstimate = cursor.real("vo2max_estimate"),
                endTsFabricated = cursor.reqLong("end_ts_fabricated"),
                importBatchId = batchId,
                source = EDGE_IMPORT_SOURCE,
            )
        }
    }

    fun readPoints(batchId: String, sink: (List<EdgeWorkoutPointRow>) -> Unit) {
        readBatches(POINT_SQL, sink) { cursor ->
            EdgeWorkoutPointRow(
                sessionId = cursor.reqString("session_id"),
                seq = cursor.reqLong("seq"),
                tsMs = cursor.reqLong("ts_ms"),
                lat = cursor.reqReal("lat"),
                lng = cursor.reqReal("lng"),
                alt = cursor.real("alt"),
                accuracy = cursor.real("accuracy"),
                speed = cursor.real("speed"),
                importBatchId = batchId,
                source = EDGE_IMPORT_SOURCE,
            )
        }
    }

    fun readSplits(batchId: String, sink: (List<EdgeWorkoutSplitRow>) -> Unit) {
        readBatches(SPLIT_SQL, sink) { cursor ->
            EdgeWorkoutSplitRow(
                sessionId = cursor.reqString("session_id"),
                km = cursor.reqLong("km"),
                meters = cursor.reqReal("meters"),
                durationSec = cursor.reqLong("duration_sec"),
                avgHr = cursor.real("avg_hr"),
                netElevM = cursor.real("net_elev_m"),
                importBatchId = batchId,
                source = EDGE_IMPORT_SOURCE,
            )
        }
    }

    fun readSets(batchId: String, sink: (List<EdgeStrengthSetRow>) -> Unit) {
        readBatches(SET_SQL, sink) { cursor ->
            EdgeStrengthSetRow(
                sessionId = cursor.reqString("session_id"),
                seq = cursor.reqLong("seq"),
                exerciseKey = cursor.reqString("exercise_key"),
                setIndex = cursor.reqLong("set_index"),
                reps = cursor.long("reps"),
                loadKg = cursor.real("load_kg"),
                rpe = cursor.long("rpe"),
                holdSec = cursor.long("hold_sec"),
                restSec = cursor.long("rest_sec"),
                atTs = cursor.long("at_ts"),
                note = cursor.text("note") ?: "",
                importBatchId = batchId,
                source = EDGE_IMPORT_SOURCE,
            )
        }
    }

    fun readSleep(batchId: String, sink: (List<EdgeSleepRow>) -> Unit) {
        readBatches(SLEEP_SQL, sink) { cursor ->
            val payload = cursor.reqString("payload_json")
            val bounds = edgeSleepBounds(payload)
            EdgeSleepRow(
                dayId = cursor.reqString("day_id"),
                algoVersion = cursor.reqLong("algo_version"),
                payloadJson = payload,
                computedAt = cursor.reqLong("computed_at"),
                onsetTs = bounds.first,
                offsetTs = bounds.second,
                importBatchId = batchId,
                source = EDGE_IMPORT_SOURCE,
            )
        }
    }

    fun readScores(batchId: String, sink: (List<EdgeDayScoreRow>) -> Unit) {
        readBatches(SCORE_SQL, sink) { cursor ->
            EdgeDayScoreRow(
                dayId = cursor.reqString("day_id"),
                algoVersion = cursor.reqLong("algo_version"),
                payloadJson = cursor.reqString("payload_json"),
                windowJson = cursor.reqString("window_json"),
                computedAt = cursor.reqLong("computed_at"),
                finalized = cursor.reqLong("finalized"),
                rhr = cursor.real("rhr"),
                rmssd = cursor.real("rmssd"),
                readiness = cursor.real("readiness"),
                skipped = cursor.reqLong("skipped"),
                partial = cursor.reqLong("partial"),
                importBatchId = batchId,
                source = EDGE_IMPORT_SOURCE,
            )
        }
    }

    fun readMetrics(batchId: String, sink: (List<EdgeMetricSeriesRow>) -> Unit) {
        readBatches(METRIC_SQL, sink) { cursor ->
            EdgeMetricSeriesRow(
                date = cursor.reqString("date"),
                key = cursor.reqString("key"),
                value = cursor.real("value"),
                importBatchId = batchId,
                source = EDGE_IMPORT_SOURCE,
            )
        }
    }

    fun readMethods(batchId: String, sink: (List<EdgeMetricMethodRow>) -> Unit) {
        readBatches(METHOD_SQL, sink) { cursor ->
            EdgeMetricMethodRow(
                date = cursor.reqString("date"),
                key = cursor.reqString("key"),
                method = cursor.reqString("method"),
                family = cursor.reqString("family"),
                metricClass = cursor.reqString("class"),
                importBatchId = batchId,
                source = EDGE_IMPORT_SOURCE,
            )
        }
    }

    fun readVersions(batchId: String, sink: (List<EdgeMetricVersionRow>) -> Unit) {
        readBatches(VERSION_SQL, sink) { cursor ->
            EdgeMetricVersionRow(
                date = cursor.reqString("date"),
                algoVersion = cursor.reqLong("algo_version"),
                edgeSource = cursor.text("source"),
                deviceFamily = cursor.text("device_family"),
                coverageDevices = cursor.text("coverage_devices"),
                priorityHash = cursor.text("priority_hash"),
                importBatchId = batchId,
                source = EDGE_IMPORT_SOURCE,
            )
        }
    }

    fun readWake(batchId: String, sink: (List<EdgeWakeFeaturesRow>) -> Unit) {
        readBatches(WAKE_SQL, sink) { cursor ->
            EdgeWakeFeaturesRow(
                dayId = cursor.reqString("day_id"),
                algoVersion = cursor.reqLong("algo_version"),
                payloadJson = cursor.reqString("payload_json"),
                computedAt = cursor.reqLong("computed_at"),
                importBatchId = batchId,
                source = EDGE_IMPORT_SOURCE,
            )
        }
    }

    fun readArchives(batchId: String, sink: (List<EdgeSubstrateArchiveRow>) -> Unit) {
        readBatches(ARCHIVE_SQL, sink) { cursor ->
            val blob = cursor.bytes("blob") ?: ByteArray(0)
            EdgeSubstrateArchiveRow(
                deviceId = cursor.reqString("device_id"),
                utcDay = cursor.reqLong("utc_day"),
                codec = cursor.reqLong("codec"),
                fromTs = cursor.reqLong("from_ts"),
                toTs = cursor.reqLong("to_ts"),
                oneHzRows = cursor.reqLong("onehz_rows"),
                rrRows = cursor.reqLong("rr_rows"),
                rawBytes = cursor.reqLong("raw_bytes"),
                fingerprint = cursor.reqLong("fingerprint"),
                tzOffsetMin = cursor.long("tz_offset_min"),
                blob = blob,
                createdAt = cursor.reqLong("created_at"),
                updatedAt = cursor.reqLong("updated_at"),
                decoded = 0,
                importBatchId = batchId,
                source = EDGE_IMPORT_SOURCE,
            )
        }
    }

    override fun close() {
        db.close()
    }

    private fun requireKnownSchema() {
        val version = userVersion()
        if (version != EDGE_BACKUP_USER_VERSION) {
            throw EdgeBackupException(
                "This file's user_version is $version. " +
                    "Pulse imports Edge backups at user_version $EDGE_BACKUP_USER_VERSION.",
            )
        }
        REQUIRED.forEach { (table, columns) ->
            if (!tableExists(table)) {
                throw EdgeBackupException("This backup is missing the $table table.")
            }
            val have = tableColumns(table)
            val missing = columns.filter { it !in have }
            if (missing.isNotEmpty()) {
                throw EdgeBackupException(
                    "This backup's $table table is missing ${missing.joinToString()}.",
                )
            }
        }
    }

    private fun warnings(): List<String> {
        val journal = textQuery("PRAGMA journal_mode")
        if (journal != null && !journal.equals("delete", ignoreCase = true)) {
            return listOf(
                "This file reports journal_mode $journal. Pulse copied only the selected file " +
                    "and did not read a sidecar.",
            )
        }
        return emptyList()
    }

    private fun userVersion(): Int = longQuery("PRAGMA user_version").toInt()

    private fun tableExists(name: String): Boolean {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(name),
        ).use { cursor -> return cursor.moveToFirst() }
    }

    private fun tableColumns(name: String): Set<String> {
        val names = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($name)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                names += cursor.getString(nameIndex)
            }
        }
        return names
    }

    private fun countRange(
        name: String,
        table: String,
        startExpr: String,
        endExpr: String,
        alreadyMillis: Boolean,
    ): EdgeCategoryPreview {
        db.rawQuery(
            "SELECT COUNT(*), MIN($startExpr), MAX($endExpr) FROM $table",
            null,
        ).use { cursor ->
            cursor.moveToFirst()
            val rows = cursor.getLong(0)
            val start = cursor.optionalLong(1)?.let { if (alreadyMillis) it else it * 1000 }
            val end = cursor.optionalLong(2)?.let { if (alreadyMillis) it else it * 1000 }
            return EdgeCategoryPreview(name, rows, start, end, null)
        }
    }

    private fun countPlain(name: String, table: String): EdgeCategoryPreview {
        return EdgeCategoryPreview(name, count(table), null, null, null)
    }

    private fun dayRange(name: String, table: String, column: String): EdgeCategoryPreview {
        db.rawQuery("SELECT COUNT(*), MIN($column), MAX($column) FROM $table", null).use { cursor ->
            cursor.moveToFirst()
            val rows = cursor.getLong(0)
            val start = cursor.optionalString(1)
            val end = cursor.optionalString(2)
            val detail = if (start != null && end != null) "Days $start to $end." else null
            return EdgeCategoryPreview(name, rows, null, null, detail)
        }
    }

    private fun samplesOnlyPreview(): EdgeCategoryPreview {
        db.rawQuery(
            "SELECT COUNT(*), MIN(s.ts_ms), MAX(s.ts_ms) FROM samples s " +
                "WHERE NOT EXISTS (" +
                "SELECT 1 FROM decoded_onehz d WHERE d.device_id = s.device_id AND d.ts_ms = s.ts_ms)",
            null,
        ).use { cursor ->
            cursor.moveToFirst()
            return EdgeCategoryPreview(
                name = EdgeCategory.SAMPLES_ONLY,
                rows = cursor.getLong(0),
                startEpochMs = cursor.optionalLong(1),
                endEpochMs = cursor.optionalLong(2),
                detail = "Imported only when decoded_onehz has no row for that device and timestamp.",
            )
        }
    }

    private fun archivePreview(): EdgeCategoryPreview {
        db.rawQuery(
            "SELECT COUNT(*), MIN(from_ts), MAX(to_ts), " +
                "COALESCE(SUM(onehz_rows), 0), COALESCE(SUM(rr_rows), 0), " +
                "MIN(codec), MAX(codec) FROM substrate_archive",
            null,
        ).use { cursor ->
            cursor.moveToFirst()
            val rows = cursor.getLong(0)
            val codec = if (rows == 0L) {
                ""
            } else if (cursor.getLong(5) == cursor.getLong(6)) {
                " Codec ${cursor.getLong(5)}."
            } else {
                " Codecs ${cursor.getLong(5)}–${cursor.getLong(6)}."
            }
            return EdgeCategoryPreview(
                name = EdgeCategory.ARCHIVES,
                rows = rows,
                startEpochMs = cursor.optionalLong(1)?.times(1000),
                endEpochMs = cursor.optionalLong(2)?.times(1000),
                detail = "Stored raw and not decoded. Declared one-Hz rows: ${cursor.getLong(3)}. " +
                    "Declared RR rows: ${cursor.getLong(4)}.$codec",
            )
        }
    }

    private fun <T> readBatches(sql: String, sink: (List<T>) -> Unit, map: (Cursor) -> T) {
        db.rawQuery(sql, null).use { cursor ->
            val batch = ArrayList<T>(BATCH)
            while (cursor.moveToNext()) {
                batch += map(cursor)
                if (batch.size == BATCH) {
                    sink(batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) sink(batch.toList())
        }
    }

    private fun longQuery(sql: String): Long {
        db.rawQuery(sql, null).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    private fun textQuery(sql: String): String? {
        db.rawQuery(sql, null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null
            return cursor.getString(0)
        }
    }

    companion object {
        const val BATCH: Int = 400

        fun open(file: File): EdgeBackup {
            if (!file.isFile) {
                throw EdgeBackupException("The backup file is missing.")
            }
            val database = try {
                SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                )
            } catch (_: SQLiteException) {
                throw EdgeBackupException("Pulse could not open this file as a SQLite database.")
            }
            return EdgeBackup(database).also { it.requireKnownSchema() }
        }
    }
}

internal fun edgeFileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun Cursor.toOneHz(batchId: String, kind: String): EdgeOneHzRow {
    return EdgeOneHzRow(
        deviceId = reqString("device_id"),
        tsMs = reqLong("ts_ms"),
        recTs = long("rec_ts"),
        counter = long("counter"),
        hr = long("hr"),
        ax = real("ax"),
        ay = real("ay"),
        az = real("az"),
        spo2RedRaw = long("spo2_red_raw"),
        spo2IrRaw = long("spo2_ir_raw"),
        skinTempRaw = long("skin_temp_raw"),
        stepCount = long("step_count"),
        stepCadence = long("step_cadence"),
        activityClass = long("activity_class"),
        skinTempC = real("skin_temp_c"),
        onWrist = long("on_wrist"),
        hrValid = long("hr_valid"),
        hrAlt = long("hr_alt"),
        ambientRaw = long("ambient_raw"),
        deviceFamily = text("device_family"),
        edgeSource = text("source"),
        tempCh2C = real("temp_ch2_c"),
        tempCh3C = real("temp_ch3_c"),
        signalQualityLogvar = real("signal_quality_logvar"),
        dynAccelG = real("dyn_accel_g"),
        tsSubsec = long("ts_subsec"),
        bandSleepState = long("band_sleep_state"),
        recordKind = kind,
        importBatchId = batchId,
        source = EDGE_IMPORT_SOURCE,
    )
}

private fun Cursor.toSampleOnly(batchId: String): EdgeOneHzRow {
    return EdgeOneHzRow(
        deviceId = reqString("device_id"),
        tsMs = reqLong("ts_ms"),
        recTs = long("ts"),
        counter = long("counter"),
        hr = long("hr"),
        ax = null,
        ay = null,
        az = null,
        spo2RedRaw = null,
        spo2IrRaw = null,
        skinTempRaw = null,
        stepCount = null,
        stepCadence = null,
        activityClass = null,
        skinTempC = null,
        onWrist = null,
        hrValid = null,
        hrAlt = null,
        ambientRaw = null,
        deviceFamily = null,
        edgeSource = null,
        tempCh2C = null,
        tempCh3C = null,
        signalQualityLogvar = null,
        dynAccelG = null,
        tsSubsec = null,
        bandSleepState = null,
        recordKind = EDGE_RECORD_SAMPLES,
        importBatchId = batchId,
        source = EDGE_IMPORT_SOURCE,
    )
}

private fun Cursor.long(name: String): Long? {
    val index = getColumnIndexOrThrow(name)
    return if (isNull(index)) null else getLong(index)
}

private fun Cursor.reqLong(name: String): Long = long(name)
    ?: throw EdgeBackupException("A $name value in the backup is null.")

private fun Cursor.real(name: String): Double? {
    val index = getColumnIndexOrThrow(name)
    return if (isNull(index)) null else getDouble(index)
}

private fun Cursor.reqReal(name: String): Double = real(name)
    ?: throw EdgeBackupException("A $name value in the backup is null.")

private fun Cursor.text(name: String): String? {
    val index = getColumnIndexOrThrow(name)
    return if (isNull(index)) null else getString(index)
}

private fun Cursor.reqString(name: String): String = text(name)
    ?: throw EdgeBackupException("A $name value in the backup is null.")

private fun Cursor.bytes(name: String): ByteArray? {
    val index = getColumnIndexOrThrow(name)
    return if (isNull(index)) null else getBlob(index)
}

private fun Cursor.optionalLong(index: Int): Long? = if (isNull(index)) null else getLong(index)

private fun Cursor.optionalString(index: Int): String? = if (isNull(index)) null else getString(index)

private const val ONE_HZ_SQL: String =
    "SELECT device_id, ts_ms, rec_ts, counter, hr, ax, ay, az, spo2_red_raw, spo2_ir_raw, " +
        "skin_temp_raw, step_count, step_cadence, activity_class, skin_temp_c, on_wrist, " +
        "hr_valid, hr_alt, ambient_raw, device_family, source, temp_ch2_c, temp_ch3_c, " +
        "signal_quality_logvar, dyn_accel_g, ts_subsec, band_sleep_state FROM decoded_onehz"

private const val SAMPLES_ONLY_SQL: String =
    "SELECT s.device_id, s.ts_ms, s.counter, s.ts, s.hr FROM samples s WHERE NOT EXISTS (" +
        "SELECT 1 FROM decoded_onehz d WHERE d.device_id = s.device_id AND d.ts_ms = s.ts_ms)"

private const val SAMPLES_ONLY_COUNT: String =
    "SELECT COUNT(*) FROM samples s WHERE NOT EXISTS (" +
        "SELECT 1 FROM decoded_onehz d WHERE d.device_id = s.device_id AND d.ts_ms = s.ts_ms)"

private const val RR_SQL: String =
    "SELECT device_id, ts_ms, rec_ts, beat_index, rr_ts_ms, rr_ms, device_family, source, " +
        "beat_ts_ms FROM decoded_rr"

private const val WORKOUT_SQL: String =
    "SELECT id, start_ts, end_ts, type, status, calories, strain, max_hr, duration_min, " +
        "zone_min_json, steps, cadence_spm, hrr_bpm, source, private, device_family, trace_json, " +
        "trace_samples, rpe, created_at, avg_hr, vo2max_estimate, end_ts_fabricated FROM sessions"

private const val POINT_SQL: String =
    "SELECT session_id, seq, ts_ms, lat, lng, alt, accuracy, speed FROM workout_route"

private const val SPLIT_SQL: String =
    "SELECT session_id, km, meters, duration_sec, avg_hr, net_elev_m FROM workout_split"

private const val SET_SQL: String =
    "SELECT session_id, seq, exercise_key, set_index, reps, load_kg, rpe, hold_sec, rest_sec, " +
        "at_ts, note FROM strength_set"

private const val SLEEP_SQL: String =
    "SELECT day_id, algo_version, payload_json, computed_at FROM sleep_session_candidates"

private const val SCORE_SQL: String =
    "SELECT day_id, algo_version, payload_json, window_json, computed_at, finalized, rhr, rmssd, " +
        "readiness, skipped, partial FROM day_result"

private const val METRIC_SQL: String = "SELECT date, key, value FROM metric_series"

private const val METHOD_SQL: String =
    "SELECT date, key, method, family, class FROM metric_method"

private const val VERSION_SQL: String =
    "SELECT date, algo_version, source, device_family, coverage_devices, priority_hash " +
        "FROM metric_series_version"

private const val WAKE_SQL: String =
    "SELECT day_id, algo_version, payload_json, computed_at FROM wake_day_features"

private const val ARCHIVE_SQL: String =
    "SELECT device_id, utc_day, codec, from_ts, to_ts, onehz_rows, rr_rows, raw_bytes, " +
        "fingerprint, tz_offset_min, blob, created_at, updated_at FROM substrate_archive"

private val COUNTABLE: Set<String> = setOf(
    "decoded_onehz",
    "decoded_rr",
    "samples",
    "sessions",
    "workout_route",
    "workout_split",
    "strength_set",
    "sleep_session_candidates",
    "day_result",
    "metric_series",
    "metric_method",
    "metric_series_version",
    "wake_day_features",
    "substrate_archive",
)

private val REQUIRED: Map<String, List<String>> = mapOf(
    "decoded_onehz" to listOf(
        "device_id", "ts_ms", "rec_ts", "counter", "hr", "ax", "ay", "az", "spo2_red_raw",
        "spo2_ir_raw", "skin_temp_raw", "step_count", "step_cadence", "activity_class",
        "skin_temp_c", "on_wrist", "hr_valid", "hr_alt", "ambient_raw", "device_family",
        "source", "temp_ch2_c", "temp_ch3_c", "signal_quality_logvar", "dyn_accel_g",
        "ts_subsec", "band_sleep_state",
    ),
    "decoded_rr" to listOf(
        "device_id", "ts_ms", "rec_ts", "beat_index", "rr_ts_ms", "rr_ms", "device_family",
        "source", "beat_ts_ms",
    ),
    "samples" to listOf("device_id", "ts_ms", "counter", "ts", "hr"),
    "sessions" to listOf(
        "id", "start_ts", "end_ts", "type", "status", "calories", "strain", "max_hr",
        "duration_min", "zone_min_json", "steps", "cadence_spm", "hrr_bpm", "source", "private",
        "device_family", "trace_json", "trace_samples", "rpe", "created_at", "avg_hr",
        "vo2max_estimate", "end_ts_fabricated",
    ),
    "workout_route" to listOf("session_id", "seq", "ts_ms", "lat", "lng", "alt", "accuracy", "speed"),
    "workout_split" to listOf("session_id", "km", "meters", "duration_sec", "avg_hr", "net_elev_m"),
    "strength_set" to listOf(
        "session_id", "seq", "exercise_key", "set_index", "reps", "load_kg", "rpe", "hold_sec",
        "rest_sec", "at_ts", "note",
    ),
    "sleep_session_candidates" to listOf("day_id", "algo_version", "payload_json", "computed_at"),
    "day_result" to listOf(
        "day_id", "algo_version", "payload_json", "window_json", "computed_at", "finalized",
        "rhr", "rmssd", "readiness", "skipped", "partial",
    ),
    "metric_series" to listOf("date", "key", "value"),
    "metric_method" to listOf("date", "key", "method", "family", "class"),
    "metric_series_version" to listOf(
        "date", "algo_version", "source", "device_family", "coverage_devices", "priority_hash",
    ),
    "wake_day_features" to listOf("day_id", "algo_version", "payload_json", "computed_at"),
    "substrate_archive" to listOf(
        "device_id", "utc_day", "codec", "from_ts", "to_ts", "onehz_rows", "rr_rows", "raw_bytes",
        "fingerprint", "tz_offset_min", "blob", "created_at", "updated_at",
    ),
)
