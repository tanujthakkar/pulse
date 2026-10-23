package app.pulse.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Opens the version-1 database (the device row only) onto the sync schema.
 *
 * The device table is not rewritten. New tables are created empty. The
 * statements match the Room export for version 2.
 */
object PulseMigrations {
    val FROM_1_TO_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            STATEMENTS.forEach { sql -> db.execSQL(sql) }
        }
    }
}

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
