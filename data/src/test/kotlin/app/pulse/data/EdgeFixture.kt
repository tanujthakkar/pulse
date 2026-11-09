package app.pulse.data

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Synthetic Edge backup. Column names follow the user_version 58 table definitions. */
internal object EdgeFixture {
    const val DEVICE: String = "strap-a"
    const val T_MS: Long = 1_763_000_000_000L
    const val T_SEC: Long = 1_763_000_000L
    const val DAY: String = "2026-11-01"
    const val OTHER_DAY: String = "2026-11-02"
    val ARCHIVE_BLOB: ByteArray = byteArrayOf(0x4f, 0x53, 0x41, 0x31, 0x01, 0x02, 0x7f)
    const val SLEEP_JSON: String =
        """{"sleep_json":{"onset_ts":1762900000,"offset_ts":1762930000,"stages":[1]},"note":"kept"}"""
    const val SCORE_JSON: String = """{"recovery":{"score":80}}"""
    const val TRACE_JSON: String = """{"n":1}"""
    const val ZONE_JSON: String = """{"z":1}"""
    const val WAKE_JSON: String = """{"a":1}"""

    fun write(
        file: File,
        userVersion: Int = 58,
        firstHr: Long = 61,
        extraOneHz: Boolean = false,
        omitTable: String? = null,
    ) {
        if (file.exists()) file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            TABLES.filter { it.first != omitTable }.forEach { (_, sql) -> db.execSQL(sql) }
            if (omitTable == null) populate(db, firstHr, extraOneHz)
            db.execSQL("PRAGMA user_version = $userVersion")
        } finally {
            db.close()
        }
    }

    private fun populate(db: SQLiteDatabase, firstHr: Long, extraOneHz: Boolean) {
        insert(
            db,
            "INSERT INTO decoded_onehz (device_id, ts_ms, rec_ts, counter, hr, ax, ay, az, " +
                "spo2_red_raw, spo2_ir_raw, skin_temp_raw, step_count, step_cadence, activity_class, " +
                "skin_temp_c, on_wrist, hr_valid, hr_alt, ambient_raw, device_family, source, " +
                "temp_ch2_c, temp_ch3_c, signal_quality_logvar, dyn_accel_g, ts_subsec, band_sleep_state) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                DEVICE, T_MS, T_SEC, 7L, firstHr, 0.1, 0.2, 0.3, 11L, 12L, 13L, 100L, 80L, 2L,
                33.5, 1L, 1L, 60L, 14L, "gen5", "history", 33.1, 32.9, 0.01, 0.05, 0L, 0L,
            ),
        )
        insert(
            db,
            "INSERT INTO decoded_onehz (device_id, ts_ms, rec_ts, counter, hr) VALUES (?,?,?,?,?)",
            listOf(DEVICE, T_MS + 1000, T_SEC + 1, 8L, 62L),
        )
        if (extraOneHz) {
            insert(
                db,
                "INSERT INTO decoded_onehz (device_id, ts_ms, rec_ts, counter, hr) VALUES (?,?,?,?,?)",
                listOf(DEVICE, T_MS + 3000, T_SEC + 3, 10L, 64L),
            )
        }
        insert(
            db,
            "INSERT INTO samples (device_id, ts_ms, counter, ts, hr) VALUES (?,?,?,?,?)",
            listOf(DEVICE, T_MS, 7L, T_SEC, 99L),
        )
        insert(
            db,
            "INSERT INTO samples (device_id, ts_ms, counter, ts, hr) VALUES (?,?,?,?,?)",
            listOf(DEVICE, T_MS + 2000, 9L, T_SEC + 2, 63L),
        )
        insert(
            db,
            "INSERT INTO decoded_rr (device_id, ts_ms, rec_ts, beat_index, rr_ts_ms, rr_ms, " +
                "device_family, source, beat_ts_ms) VALUES (?,?,?,?,?,?,?,?,?)",
            listOf(DEVICE, T_MS, T_SEC, 0L, T_MS + 10, 800L, "gen5", "history", T_MS + 10),
        )
        insert(
            db,
            "INSERT INTO decoded_rr (device_id, ts_ms, rec_ts, beat_index, rr_ts_ms, rr_ms, " +
                "device_family, source, beat_ts_ms) VALUES (?,?,?,?,?,?,?,?,?)",
            listOf(DEVICE, T_MS + 1000, T_SEC + 1, 0L, T_MS + 1010, 810L, "gen5", "history", T_MS + 1010),
        )
        insert(
            db,
            "INSERT INTO sessions (id, start_ts, end_ts, type, status, calories, strain, max_hr, " +
                "duration_min, zone_min_json, steps, cadence_spm, hrr_bpm, source, private, " +
                "device_family, trace_json, trace_samples, rpe, created_at, avg_hr, vo2max_estimate, " +
                "end_ts_fabricated) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                "run-1", T_SEC, T_SEC + 600, "running", "done", 200.5, 5.5, 160L, 10L, ZONE_JSON,
                1000L, 170L, 20.5, "manual", 0L, "gen5", TRACE_JSON, 600L, 7.5, T_MS, 140L, 45.5, 0L,
            ),
        )
        insert(
            db,
            "INSERT INTO sessions (id, start_ts, end_ts, type, status, source, private, created_at, " +
                "end_ts_fabricated) VALUES (?,?,?,?,?,?,?,?,?)",
            listOf("str-1", T_SEC + 7200, T_SEC + 8000, "weight_training", "done", "manual", 0L, T_MS, 0L),
        )
        insert(
            db,
            "INSERT INTO workout_route (session_id, seq, ts_ms, lat, lng, alt, accuracy, speed) " +
                "VALUES (?,?,?,?,?,?,?,?)",
            listOf("run-1", 0L, T_MS, 40.0, -70.0, 10.0, 5.0, 3.0),
        )
        insert(
            db,
            "INSERT INTO workout_route (session_id, seq, ts_ms, lat, lng, alt, accuracy, speed) " +
                "VALUES (?,?,?,?,?,?,?,?)",
            listOf("run-1", 1L, T_MS + 1000, 40.1, -70.1, 11.0, 4.0, 3.2),
        )
        insert(
            db,
            "INSERT INTO workout_split (session_id, km, meters, duration_sec, avg_hr, net_elev_m) " +
                "VALUES (?,?,?,?,?,?)",
            listOf("run-1", 1L, 1000.5, 300L, 150.0, 4.0),
        )
        insert(
            db,
            "INSERT INTO strength_set (session_id, seq, exercise_key, set_index, reps, load_kg, rpe, " +
                "hold_sec, rest_sec, at_ts, note) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            listOf("str-1", 0L, "squat", 1L, 5L, 40.0, 8L, 0L, 60L, T_SEC + 7200, "easy"),
        )
        insert(
            db,
            "INSERT INTO sleep_session_candidates (day_id, algo_version, payload_json, computed_at) " +
                "VALUES (?,?,?,?)",
            listOf(DAY, 3L, SLEEP_JSON, T_MS),
        )
        insert(
            db,
            "INSERT INTO sleep_session_candidates (day_id, algo_version, payload_json, computed_at) " +
                "VALUES (?,?,?,?)",
            listOf(OTHER_DAY, 1L, "not-json", T_MS + 1),
        )
        insert(
            db,
            "INSERT INTO day_result (day_id, algo_version, payload_json, window_json, computed_at, " +
                "finalized, rhr, rmssd, readiness, skipped, partial) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            listOf(DAY, 3L, SCORE_JSON, "{}", T_MS, 1L, 48.5, 41.25, null, 0L, 0L),
        )
        insert(
            db,
            "INSERT INTO metric_series (date, key, value) VALUES (?,?,?)",
            listOf(DAY, "hrv", 41.25),
        )
        insert(
            db,
            "INSERT INTO metric_method (date, key, method, family, class) VALUES (?,?,?,?,?)",
            listOf(DAY, "hrv", "rmssd", "recovery", "night"),
        )
        insert(
            db,
            "INSERT INTO metric_series_version (date, algo_version, source, device_family, " +
                "coverage_devices, priority_hash) VALUES (?,?,?,?,?,?)",
            listOf(DAY, 3L, "decoded", "gen5", DEVICE, "abc"),
        )
        insert(
            db,
            "INSERT INTO wake_day_features (day_id, algo_version, payload_json, computed_at) " +
                "VALUES (?,?,?,?)",
            listOf(DAY, 3L, WAKE_JSON, T_MS),
        )
        insert(
            db,
            "INSERT INTO substrate_archive (device_id, utc_day, codec, from_ts, to_ts, onehz_rows, " +
                "rr_rows, raw_bytes, fingerprint, tz_offset_min, blob, created_at, updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                DEVICE, 20747L, 1L, T_SEC - 86_400, T_SEC - 1, 100L, 40L, 5000L, 99L, -240L,
                ARCHIVE_BLOB, T_MS, T_MS,
            ),
        )
    }

    private fun insert(db: SQLiteDatabase, sql: String, values: List<Any?>) {
        val statement = db.compileStatement(sql)
        try {
            values.forEachIndexed { index, value ->
                val position = index + 1
                when (value) {
                    null -> statement.bindNull(position)
                    is Long -> statement.bindLong(position, value)
                    is Double -> statement.bindDouble(position, value)
                    is String -> statement.bindString(position, value)
                    is ByteArray -> statement.bindBlob(position, value)
                    else -> error("unsupported bind ${value.javaClass}")
                }
            }
            statement.executeInsert()
        } finally {
            statement.close()
        }
    }

    private val TABLES: List<Pair<String, String>> = listOf(
        "decoded_onehz" to """
            CREATE TABLE decoded_onehz (
                device_id TEXT NOT NULL DEFAULT '',
                ts_ms INTEGER NOT NULL DEFAULT 0,
                rec_ts INTEGER,
                counter INTEGER NOT NULL,
                hr INTEGER,
                ax REAL, ay REAL, az REAL,
                spo2_red_raw INTEGER, spo2_ir_raw INTEGER, skin_temp_raw INTEGER,
                step_count INTEGER, step_cadence INTEGER, activity_class INTEGER,
                skin_temp_c REAL, on_wrist INTEGER, hr_valid INTEGER, hr_alt INTEGER,
                ambient_raw INTEGER, device_family TEXT, source TEXT,
                temp_ch2_c REAL, temp_ch3_c REAL, signal_quality_logvar REAL,
                dyn_accel_g REAL, ts_subsec INTEGER, band_sleep_state INTEGER,
                PRIMARY KEY (device_id, ts_ms)
            )
        """.trimIndent(),
        "decoded_rr" to """
            CREATE TABLE decoded_rr (
                device_id TEXT NOT NULL DEFAULT '',
                ts_ms INTEGER NOT NULL DEFAULT 0,
                rec_ts INTEGER NOT NULL,
                beat_index INTEGER NOT NULL,
                rr_ts_ms INTEGER NOT NULL,
                rr_ms INTEGER NOT NULL,
                device_family TEXT,
                source TEXT,
                beat_ts_ms INTEGER,
                PRIMARY KEY (device_id, ts_ms, beat_index)
            )
        """.trimIndent(),
        "samples" to """
            CREATE TABLE samples (
                device_id TEXT NOT NULL DEFAULT '',
                ts_ms INTEGER NOT NULL DEFAULT 0,
                counter INTEGER,
                ts INTEGER NOT NULL,
                hr INTEGER,
                PRIMARY KEY (device_id, ts_ms)
            )
        """.trimIndent(),
        "sessions" to """
            CREATE TABLE sessions (
                id TEXT PRIMARY KEY,
                start_ts INTEGER NOT NULL,
                end_ts INTEGER,
                type TEXT NOT NULL,
                status TEXT NOT NULL,
                calories REAL,
                strain REAL,
                max_hr INTEGER,
                duration_min INTEGER,
                zone_min_json TEXT,
                steps INTEGER,
                cadence_spm INTEGER,
                hrr_bpm REAL,
                source TEXT NOT NULL DEFAULT 'manual',
                private INTEGER NOT NULL DEFAULT 0,
                device_family TEXT,
                trace_json TEXT,
                trace_samples INTEGER,
                rpe REAL,
                created_at INTEGER NOT NULL,
                avg_hr INTEGER,
                vo2max_estimate REAL,
                end_ts_fabricated INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent(),
        "workout_route" to """
            CREATE TABLE workout_route (
                session_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                ts_ms INTEGER NOT NULL,
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                alt REAL,
                accuracy REAL,
                speed REAL,
                PRIMARY KEY (session_id, seq)
            )
        """.trimIndent(),
        "workout_split" to """
            CREATE TABLE workout_split (
                session_id TEXT NOT NULL,
                km INTEGER NOT NULL,
                meters REAL NOT NULL,
                duration_sec INTEGER NOT NULL,
                avg_hr REAL,
                net_elev_m REAL,
                PRIMARY KEY (session_id, km)
            )
        """.trimIndent(),
        "strength_set" to """
            CREATE TABLE strength_set (
                session_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                exercise_key TEXT NOT NULL,
                set_index INTEGER NOT NULL,
                reps INTEGER,
                load_kg REAL,
                rpe INTEGER,
                hold_sec INTEGER,
                rest_sec INTEGER,
                at_ts INTEGER,
                note TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (session_id, seq)
            )
        """.trimIndent(),
        "sleep_session_candidates" to """
            CREATE TABLE sleep_session_candidates (
                day_id TEXT NOT NULL,
                algo_version INTEGER NOT NULL,
                payload_json TEXT NOT NULL,
                computed_at INTEGER NOT NULL,
                PRIMARY KEY (day_id, algo_version)
            )
        """.trimIndent(),
        "day_result" to """
            CREATE TABLE day_result (
                day_id TEXT NOT NULL,
                algo_version INTEGER NOT NULL,
                payload_json TEXT NOT NULL,
                window_json TEXT NOT NULL DEFAULT '{}',
                computed_at INTEGER NOT NULL,
                finalized INTEGER NOT NULL DEFAULT 0,
                rhr REAL,
                rmssd REAL,
                readiness REAL,
                skipped INTEGER NOT NULL DEFAULT 0,
                partial INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (day_id, algo_version)
            )
        """.trimIndent(),
        "metric_series" to """
            CREATE TABLE metric_series (
                date TEXT NOT NULL,
                key TEXT NOT NULL,
                value REAL,
                PRIMARY KEY (date, key)
            )
        """.trimIndent(),
        "metric_method" to """
            CREATE TABLE metric_method (
                date TEXT NOT NULL,
                key TEXT NOT NULL,
                method TEXT NOT NULL,
                family TEXT NOT NULL,
                class TEXT NOT NULL,
                PRIMARY KEY (date, key)
            )
        """.trimIndent(),
        "metric_series_version" to """
            CREATE TABLE metric_series_version (
                date TEXT PRIMARY KEY,
                algo_version INTEGER NOT NULL,
                source TEXT,
                device_family TEXT,
                coverage_devices TEXT,
                priority_hash TEXT
            )
        """.trimIndent(),
        "wake_day_features" to """
            CREATE TABLE wake_day_features (
                day_id TEXT NOT NULL,
                algo_version INTEGER NOT NULL,
                payload_json TEXT NOT NULL,
                computed_at INTEGER NOT NULL,
                PRIMARY KEY (day_id, algo_version)
            )
        """.trimIndent(),
        "substrate_archive" to """
            CREATE TABLE substrate_archive (
                device_id TEXT NOT NULL DEFAULT '',
                utc_day INTEGER NOT NULL,
                codec INTEGER NOT NULL,
                from_ts INTEGER NOT NULL,
                to_ts INTEGER NOT NULL,
                onehz_rows INTEGER NOT NULL,
                rr_rows INTEGER NOT NULL,
                raw_bytes INTEGER NOT NULL,
                fingerprint INTEGER NOT NULL,
                tz_offset_min INTEGER,
                blob BLOB NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (device_id, utc_day)
            )
        """.trimIndent(),
    )
}
