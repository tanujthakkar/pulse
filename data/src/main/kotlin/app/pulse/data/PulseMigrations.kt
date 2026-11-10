package app.pulse.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Opens an older database onto the current sync schema.
 *
 * Version 1 is the device row only. Version 2 adds the sync tables. Version 3
 * adds `r18_sample.decoder_version`. Version 4 adds
 * `sync_session.range_snapshot`, the 65-byte data-range body. Version 5 adds
 * the Edge import tables. The device table is not rewritten. Existing
 * layout-18 rows gain decoder version 0, meaning they were stored before a
 * decoder stamped them. New commits write the decoder's own version. Sessions
 * opened before version 4 keep a null snapshot; that is not a captured range.
 * Version 5 does not rewrite strap rows. Imported Edge rows live in the new
 * tables, each labeled `source` = `edge_import`. Version 6 adds
 * `manual_activity` and does not rewrite earlier rows.
 */
object PulseMigrations {
    val FROM_1_TO_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            STATEMENTS.forEach { sql -> db.execSQL(sql) }
        }
    }

    val FROM_2_TO_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `r18_sample` ADD COLUMN `decoder_version` INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    val FROM_3_TO_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `sync_session` ADD COLUMN `range_snapshot` BLOB")
        }
    }

    val FROM_4_TO_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            EDGE_IMPORT_TABLES.forEach { sql -> db.execSQL(sql) }
        }
    }

    val FROM_5_TO_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(MANUAL_ACTIVITY_TABLE)
        }
    }
}

/**
 * Room's expected create statement for [ManualActivityRow].
 *
 * Auto-generated ids, a type, and an inclusive start with an exclusive end.
 */
internal const val MANUAL_ACTIVITY_TABLE: String =
    "CREATE TABLE IF NOT EXISTS `manual_activity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`type` TEXT NOT NULL, `start_unix` INTEGER NOT NULL, `end_unix` INTEGER NOT NULL)"

private val EDGE_IMPORT_TABLES: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `edge_import_batch` (`id` TEXT NOT NULL, " +
        "`created_at_ms` INTEGER NOT NULL, `source` TEXT NOT NULL, `user_version` INTEGER NOT NULL, " +
        "`byte_size` INTEGER NOT NULL, `sha256` TEXT NOT NULL, `inserted_rows` INTEGER NOT NULL, " +
        "`already_present` INTEGER NOT NULL, `skipped_strap_overlap` INTEGER NOT NULL, " +
        "PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `edge_onehz` (`device_id` TEXT NOT NULL, `ts_ms` INTEGER NOT NULL, " +
        "`rec_ts` INTEGER, `counter` INTEGER, `hr` INTEGER, `ax` REAL, `ay` REAL, `az` REAL, " +
        "`spo2_red_raw` INTEGER, `spo2_ir_raw` INTEGER, `skin_temp_raw` INTEGER, " +
        "`step_count` INTEGER, `step_cadence` INTEGER, `activity_class` INTEGER, " +
        "`skin_temp_c` REAL, `on_wrist` INTEGER, `hr_valid` INTEGER, `hr_alt` INTEGER, " +
        "`ambient_raw` INTEGER, `device_family` TEXT, `edge_source` TEXT, `temp_ch2_c` REAL, " +
        "`temp_ch3_c` REAL, `signal_quality_logvar` REAL, `dyn_accel_g` REAL, `ts_subsec` INTEGER, " +
        "`band_sleep_state` INTEGER, `record_kind` TEXT NOT NULL, `import_batch_id` TEXT NOT NULL, " +
        "`source` TEXT NOT NULL, PRIMARY KEY(`device_id`, `ts_ms`))",
    "CREATE INDEX IF NOT EXISTS `index_edge_onehz_ts_ms` ON `edge_onehz` (`ts_ms`)",
    "CREATE TABLE IF NOT EXISTS `edge_rr` (`device_id` TEXT NOT NULL, `ts_ms` INTEGER NOT NULL, " +
        "`rec_ts` INTEGER NOT NULL, `beat_index` INTEGER NOT NULL, `rr_ts_ms` INTEGER NOT NULL, " +
        "`rr_ms` INTEGER NOT NULL, `device_family` TEXT, `edge_source` TEXT, `beat_ts_ms` INTEGER, " +
        "`import_batch_id` TEXT NOT NULL, `source` TEXT NOT NULL, " +
        "PRIMARY KEY(`device_id`, `ts_ms`, `beat_index`))",
    "CREATE TABLE IF NOT EXISTS `edge_workout` (`id` TEXT NOT NULL, `start_ts` INTEGER NOT NULL, " +
        "`end_ts` INTEGER, `type` TEXT NOT NULL, `status` TEXT NOT NULL, `calories` REAL, " +
        "`strain` REAL, `max_hr` INTEGER, `duration_min` INTEGER, `zone_min_json` TEXT, " +
        "`steps` INTEGER, `cadence_spm` INTEGER, `hrr_bpm` REAL, `edge_source` TEXT NOT NULL, " +
        "`private` INTEGER NOT NULL, `device_family` TEXT, `trace_json` TEXT, " +
        "`trace_samples` INTEGER, `rpe` REAL, `created_at` INTEGER NOT NULL, `avg_hr` INTEGER, " +
        "`vo2max_estimate` REAL, `end_ts_fabricated` INTEGER NOT NULL, " +
        "`import_batch_id` TEXT NOT NULL, `source` TEXT NOT NULL, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `edge_workout_point` (`session_id` TEXT NOT NULL, " +
        "`seq` INTEGER NOT NULL, `ts_ms` INTEGER NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, " +
        "`alt` REAL, `accuracy` REAL, `speed` REAL, `import_batch_id` TEXT NOT NULL, " +
        "`source` TEXT NOT NULL, PRIMARY KEY(`session_id`, `seq`))",
    "CREATE TABLE IF NOT EXISTS `edge_workout_split` (`session_id` TEXT NOT NULL, " +
        "`km` INTEGER NOT NULL, `meters` REAL NOT NULL, `duration_sec` INTEGER NOT NULL, " +
        "`avg_hr` REAL, `net_elev_m` REAL, `import_batch_id` TEXT NOT NULL, `source` TEXT NOT NULL, " +
        "PRIMARY KEY(`session_id`, `km`))",
    "CREATE TABLE IF NOT EXISTS `edge_strength_set` (`session_id` TEXT NOT NULL, " +
        "`seq` INTEGER NOT NULL, `exercise_key` TEXT NOT NULL, `set_index` INTEGER NOT NULL, " +
        "`reps` INTEGER, `load_kg` REAL, `rpe` INTEGER, `hold_sec` INTEGER, `rest_sec` INTEGER, " +
        "`at_ts` INTEGER, `note` TEXT NOT NULL, `import_batch_id` TEXT NOT NULL, " +
        "`source` TEXT NOT NULL, PRIMARY KEY(`session_id`, `seq`))",
    "CREATE TABLE IF NOT EXISTS `edge_sleep` (`day_id` TEXT NOT NULL, `algo_version` INTEGER NOT NULL, " +
        "`payload_json` TEXT NOT NULL, `computed_at` INTEGER NOT NULL, `onset_ts` INTEGER, " +
        "`offset_ts` INTEGER, `import_batch_id` TEXT NOT NULL, `source` TEXT NOT NULL, " +
        "PRIMARY KEY(`day_id`, `algo_version`))",
    "CREATE TABLE IF NOT EXISTS `edge_day_score` (`day_id` TEXT NOT NULL, " +
        "`algo_version` INTEGER NOT NULL, `payload_json` TEXT NOT NULL, `window_json` TEXT NOT NULL, " +
        "`computed_at` INTEGER NOT NULL, `finalized` INTEGER NOT NULL, `rhr` REAL, `rmssd` REAL, " +
        "`readiness` REAL, `skipped` INTEGER NOT NULL, `partial` INTEGER NOT NULL, " +
        "`import_batch_id` TEXT NOT NULL, `source` TEXT NOT NULL, " +
        "PRIMARY KEY(`day_id`, `algo_version`))",
    "CREATE TABLE IF NOT EXISTS `edge_metric_series` (`date` TEXT NOT NULL, `key` TEXT NOT NULL, " +
        "`value` REAL, `import_batch_id` TEXT NOT NULL, `source` TEXT NOT NULL, " +
        "PRIMARY KEY(`date`, `key`))",
    "CREATE TABLE IF NOT EXISTS `edge_metric_method` (`date` TEXT NOT NULL, `key` TEXT NOT NULL, " +
        "`method` TEXT NOT NULL, `family` TEXT NOT NULL, `class` TEXT NOT NULL, " +
        "`import_batch_id` TEXT NOT NULL, `source` TEXT NOT NULL, PRIMARY KEY(`date`, `key`))",
    "CREATE TABLE IF NOT EXISTS `edge_metric_version` (`date` TEXT NOT NULL, " +
        "`algo_version` INTEGER NOT NULL, `edge_source` TEXT, `device_family` TEXT, " +
        "`coverage_devices` TEXT, `priority_hash` TEXT, `import_batch_id` TEXT NOT NULL, " +
        "`source` TEXT NOT NULL, PRIMARY KEY(`date`))",
    "CREATE TABLE IF NOT EXISTS `edge_wake_features` (`day_id` TEXT NOT NULL, " +
        "`algo_version` INTEGER NOT NULL, `payload_json` TEXT NOT NULL, " +
        "`computed_at` INTEGER NOT NULL, `import_batch_id` TEXT NOT NULL, `source` TEXT NOT NULL, " +
        "PRIMARY KEY(`day_id`, `algo_version`))",
    "CREATE TABLE IF NOT EXISTS `edge_substrate_archive` (`device_id` TEXT NOT NULL, " +
        "`utc_day` INTEGER NOT NULL, `codec` INTEGER NOT NULL, `from_ts` INTEGER NOT NULL, " +
        "`to_ts` INTEGER NOT NULL, `onehz_rows` INTEGER NOT NULL, `rr_rows` INTEGER NOT NULL, " +
        "`raw_bytes` INTEGER NOT NULL, `fingerprint` INTEGER NOT NULL, `tz_offset_min` INTEGER, " +
        "`blob` BLOB NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
        "`decoded` INTEGER NOT NULL, `import_batch_id` TEXT NOT NULL, `source` TEXT NOT NULL, " +
        "PRIMARY KEY(`device_id`, `utc_day`))",
)

private val STATEMENTS: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `sync_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`device_id` TEXT NOT NULL, `started_unix_seconds` INTEGER NOT NULL, " +
        "`started_subsecond_ticks` INTEGER, `ended_unix_seconds` INTEGER, " +
        "`ended_subsecond_ticks` INTEGER, FOREIGN KEY(`device_id`) REFERENCES `device`(`address`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_sync_session_device_id` ON `sync_session` (`device_id`)",
    "CREATE TABLE IF NOT EXISTS `sync_chunk` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`session_id` INTEGER NOT NULL, `device_id` TEXT NOT NULL, `token` BLOB NOT NULL, " +
        "`frame_count` INTEGER NOT NULL, `sample_count` INTEGER NOT NULL, " +
        "`archived_count` INTEGER NOT NULL, `stored_unix_seconds` INTEGER NOT NULL, " +
        "`stored_subsecond_ticks` INTEGER, `acked_at` INTEGER, " +
        "FOREIGN KEY(`session_id`) REFERENCES `sync_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
        "FOREIGN KEY(`device_id`) REFERENCES `device`(`address`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_chunk_device_id_token` " +
        "ON `sync_chunk` (`device_id`, `token`)",
    "CREATE INDEX IF NOT EXISTS `index_sync_chunk_session_id` ON `sync_chunk` (`session_id`)",
    "CREATE TABLE IF NOT EXISTS `raw_frame` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`device_id` TEXT NOT NULL, `chunk_id` INTEGER NOT NULL, `sha256` BLOB NOT NULL, " +
        "`packet_type` INTEGER, `layout` INTEGER, `record_index` INTEGER, `unix_seconds` INTEGER, " +
        "`subsecond_ticks` INTEGER, `archived` INTEGER NOT NULL, `payload` BLOB NOT NULL, " +
        "FOREIGN KEY(`device_id`) REFERENCES `device`(`address`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
        "FOREIGN KEY(`chunk_id`) REFERENCES `sync_chunk`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_raw_frame_device_id_sha256` " +
        "ON `raw_frame` (`device_id`, `sha256`)",
    "CREATE INDEX IF NOT EXISTS `index_raw_frame_chunk_id` ON `raw_frame` (`chunk_id`)",
    "CREATE TABLE IF NOT EXISTS `r18_sample` (`device_id` TEXT NOT NULL, `record_index` INTEGER NOT NULL, " +
        "`chunk_id` INTEGER NOT NULL, `unix_s` INTEGER NOT NULL, `subsecond_ticks` INTEGER, " +
        "`heart_rate_bpm` INTEGER, `heart_rate_raw` INTEGER NOT NULL, `rr_count` INTEGER NOT NULL, " +
        "`rr_tick_0` INTEGER, `rr_tick_1` INTEGER, `rr_tick_2` INTEGER, `rr_tick_3` INTEGER, " +
        "`rr_block_raw` BLOB NOT NULL, `cardiac_raw` INTEGER NOT NULL, `quality_raw` INTEGER NOT NULL, " +
        "`alternate_hr_raw` INTEGER NOT NULL, `packed_word_raw` INTEGER NOT NULL, " +
        "`dynamic_accel` REAL, `dynamic_accel_bits` INTEGER NOT NULL, `axis_accel_0` REAL, " +
        "`axis_accel_1` REAL, `axis_accel_2` REAL, `axis_accel_0_bits` INTEGER NOT NULL, " +
        "`axis_accel_1_bits` INTEGER NOT NULL, `axis_accel_2_bits` INTEGER NOT NULL, " +
        "`step_counter` INTEGER NOT NULL, `cadence_raw` INTEGER NOT NULL, " +
        "`hardware_counter` INTEGER NOT NULL, `activity_class` INTEGER, " +
        "`activity_class_raw` INTEGER NOT NULL, `counter_override_raw` INTEGER NOT NULL, " +
        "`aux_temp_0_c` REAL, `aux_temp_1_c` REAL, `aux_temp_0_raw` INTEGER NOT NULL, " +
        "`aux_temp_1_raw` INTEGER NOT NULL, `skin_temp_c` REAL, `skin_temp_raw` INTEGER NOT NULL, " +
        "`status_0_raw` INTEGER NOT NULL, `status_1_raw` INTEGER NOT NULL, `status_2_raw` INTEGER NOT NULL, " +
        "`motion_raw` INTEGER NOT NULL, `rest_state` INTEGER, `sleep_adjacent_raw` INTEGER NOT NULL, " +
        "`crc32` INTEGER NOT NULL, `raw` BLOB NOT NULL, " +
        "PRIMARY KEY(`device_id`, `record_index`, `unix_s`), " +
        "FOREIGN KEY(`device_id`) REFERENCES `device`(`address`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
        "FOREIGN KEY(`chunk_id`) REFERENCES `sync_chunk`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_r18_sample_chunk_id` ON `r18_sample` (`chunk_id`)",
    "CREATE TABLE IF NOT EXISTS `device_battery` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`device_id` TEXT NOT NULL, `unix_seconds` INTEGER NOT NULL, `subsecond_ticks` INTEGER, " +
        "`source` TEXT NOT NULL, `percent` INTEGER, `percent_raw` INTEGER, `millivolts` INTEGER, " +
        "`charging` INTEGER, `raw` BLOB, FOREIGN KEY(`device_id`) REFERENCES `device`(`address`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_device_battery_device_id` ON `device_battery` (`device_id`)",
)
