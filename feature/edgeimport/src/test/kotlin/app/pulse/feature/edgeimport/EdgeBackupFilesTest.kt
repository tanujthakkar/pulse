package app.pulse.feature.edgeimport

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.pulse.data.EdgeImporter
import app.pulse.data.PulseDatabase
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EdgeBackupFilesTest {
    private var database: PulseDatabase? = null

    @After
    fun tearDown() {
        database?.close()
    }

    @Test
    fun copyLeavesTheSourceBytesAndMarksTheCacheReadOnly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "edge-source.db")
        source.writeBytes(byteArrayOf(1, 2, 3, 4))
        val modified = source.lastModified()
        val copy = source.inputStream().use { EdgeBackupFiles.writeCache(context, it) }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), source.readBytes())
        assertEquals(modified, source.lastModified())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), copy.readBytes())
        assertFalse(copy.canWrite())
        source.delete()
        copy.delete()
    }

    @Test
    fun viewModelPreviewsThenCommitsWithoutChangingTheFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database = db
        val source = File(context.cacheDir, "edge-vm.db")
        writeTinyBackup(source)
        val before = source.readBytes()
        val copy = source.inputStream().use { EdgeBackupFiles.writeCache(context, it) }
        val model = EdgeImportViewModel(EdgeImporter(db, newId = { "vm-batch" }), context)
        model.onBackupFile(copy)
        val ready = await(model) as ImportUiState.Ready
        assertEquals(1L, ready.preview.categories.first { it.name == "One-second heart rate" }.rows)
        model.commit()
        val finished = await(model) as ImportUiState.Finished
        assertTrue(finished.summary.contains("Imported as edge_import."))
        assertTrue(finished.summary.contains("Batch vm-batch."))
        assertTrue(finished.summary.contains("Decoded: 0."))
        assertEquals(1, db.edgeImport().oneHzCount())
        assertEquals(0, db.edgeImport().archiveCount())
        assertArrayEquals(before, source.readBytes())
        assertFalse(copy.exists())
        source.delete()
    }

    private fun await(model: EdgeImportViewModel): ImportUiState {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val state = model.state.value
            if (state !is ImportUiState.Idle && state !is ImportUiState.Reading && state !is ImportUiState.Running) {
                return state
            }
            Thread.sleep(20)
        }
        return model.state.value
    }

    private fun writeTinyBackup(file: File) {
        if (file.exists()) file.delete()
        val sqlite = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            listOf(
                "decoded_onehz", "decoded_rr", "samples", "sessions", "workout_route", "workout_split",
                "strength_set", "sleep_session_candidates", "day_result", "metric_series", "metric_method",
                "metric_series_version", "wake_day_features", "substrate_archive",
            ).forEach { table ->
                sqlite.execSQL(emptyTable(table))
            }
            sqlite.execSQL(
                "INSERT INTO decoded_onehz (device_id, ts_ms, rec_ts, counter, hr) VALUES ('strap-a', 1000, 1, 1, 55)",
            )
            sqlite.execSQL("PRAGMA user_version = 58")
        } finally {
            sqlite.close()
        }
    }

    private fun emptyTable(name: String): String = when (name) {
        "decoded_onehz" ->
            "CREATE TABLE decoded_onehz (device_id TEXT NOT NULL, ts_ms INTEGER NOT NULL, rec_ts INTEGER, " +
                "counter INTEGER NOT NULL, hr INTEGER, ax REAL, ay REAL, az REAL, spo2_red_raw INTEGER, " +
                "spo2_ir_raw INTEGER, skin_temp_raw INTEGER, step_count INTEGER, step_cadence INTEGER, " +
                "activity_class INTEGER, skin_temp_c REAL, on_wrist INTEGER, hr_valid INTEGER, hr_alt INTEGER, " +
                "ambient_raw INTEGER, device_family TEXT, source TEXT, temp_ch2_c REAL, temp_ch3_c REAL, " +
                "signal_quality_logvar REAL, dyn_accel_g REAL, ts_subsec INTEGER, band_sleep_state INTEGER, " +
                "PRIMARY KEY (device_id, ts_ms))"
        "decoded_rr" ->
            "CREATE TABLE decoded_rr (device_id TEXT NOT NULL, ts_ms INTEGER NOT NULL, rec_ts INTEGER NOT NULL, " +
                "beat_index INTEGER NOT NULL, rr_ts_ms INTEGER NOT NULL, rr_ms INTEGER NOT NULL, " +
                "device_family TEXT, source TEXT, beat_ts_ms INTEGER, PRIMARY KEY (device_id, ts_ms, beat_index))"
        "samples" ->
            "CREATE TABLE samples (device_id TEXT NOT NULL, ts_ms INTEGER NOT NULL, counter INTEGER, " +
                "ts INTEGER NOT NULL, hr INTEGER, PRIMARY KEY (device_id, ts_ms))"
        "sessions" ->
            "CREATE TABLE sessions (id TEXT PRIMARY KEY, start_ts INTEGER NOT NULL, end_ts INTEGER, " +
                "type TEXT NOT NULL, status TEXT NOT NULL, calories REAL, strain REAL, max_hr INTEGER, " +
                "duration_min INTEGER, zone_min_json TEXT, steps INTEGER, cadence_spm INTEGER, hrr_bpm REAL, " +
                "source TEXT NOT NULL, private INTEGER NOT NULL, device_family TEXT, trace_json TEXT, " +
                "trace_samples INTEGER, rpe REAL, created_at INTEGER NOT NULL, avg_hr INTEGER, " +
                "vo2max_estimate REAL, end_ts_fabricated INTEGER NOT NULL)"
        "workout_route" ->
            "CREATE TABLE workout_route (session_id TEXT NOT NULL, seq INTEGER NOT NULL, ts_ms INTEGER NOT NULL, " +
                "lat REAL NOT NULL, lng REAL NOT NULL, alt REAL, accuracy REAL, speed REAL, " +
                "PRIMARY KEY (session_id, seq))"
        "workout_split" ->
            "CREATE TABLE workout_split (session_id TEXT NOT NULL, km INTEGER NOT NULL, meters REAL NOT NULL, " +
                "duration_sec INTEGER NOT NULL, avg_hr REAL, net_elev_m REAL, PRIMARY KEY (session_id, km))"
        "strength_set" ->
            "CREATE TABLE strength_set (session_id TEXT NOT NULL, seq INTEGER NOT NULL, " +
                "exercise_key TEXT NOT NULL, set_index INTEGER NOT NULL, reps INTEGER, load_kg REAL, rpe INTEGER, " +
                "hold_sec INTEGER, rest_sec INTEGER, at_ts INTEGER, note TEXT NOT NULL, PRIMARY KEY (session_id, seq))"
        "sleep_session_candidates" ->
            "CREATE TABLE sleep_session_candidates (day_id TEXT NOT NULL, algo_version INTEGER NOT NULL, " +
                "payload_json TEXT NOT NULL, computed_at INTEGER NOT NULL, PRIMARY KEY (day_id, algo_version))"
        "day_result" ->
            "CREATE TABLE day_result (day_id TEXT NOT NULL, algo_version INTEGER NOT NULL, " +
                "payload_json TEXT NOT NULL, window_json TEXT NOT NULL, computed_at INTEGER NOT NULL, " +
                "finalized INTEGER NOT NULL, rhr REAL, rmssd REAL, readiness REAL, skipped INTEGER NOT NULL, " +
                "partial INTEGER NOT NULL, PRIMARY KEY (day_id, algo_version))"
        "metric_series" ->
            "CREATE TABLE metric_series (date TEXT NOT NULL, key TEXT NOT NULL, value REAL, PRIMARY KEY (date, key))"
        "metric_method" ->
            "CREATE TABLE metric_method (date TEXT NOT NULL, key TEXT NOT NULL, method TEXT NOT NULL, " +
                "family TEXT NOT NULL, class TEXT NOT NULL, PRIMARY KEY (date, key))"
        "metric_series_version" ->
            "CREATE TABLE metric_series_version (date TEXT PRIMARY KEY, algo_version INTEGER NOT NULL, " +
                "source TEXT, device_family TEXT, coverage_devices TEXT, priority_hash TEXT)"
        "wake_day_features" ->
            "CREATE TABLE wake_day_features (day_id TEXT NOT NULL, algo_version INTEGER NOT NULL, " +
                "payload_json TEXT NOT NULL, computed_at INTEGER NOT NULL, PRIMARY KEY (day_id, algo_version))"
        "substrate_archive" ->
            "CREATE TABLE substrate_archive (device_id TEXT NOT NULL, utc_day INTEGER NOT NULL, " +
                "codec INTEGER NOT NULL, from_ts INTEGER NOT NULL, to_ts INTEGER NOT NULL, " +
                "onehz_rows INTEGER NOT NULL, rr_rows INTEGER NOT NULL, raw_bytes INTEGER NOT NULL, " +
                "fingerprint INTEGER NOT NULL, tz_offset_min INTEGER, blob BLOB NOT NULL, " +
                "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY (device_id, utc_day))"
        else -> error(name)
    }
}
