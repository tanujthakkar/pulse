package app.pulse.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One committed read of an Edge backup.
 *
 * Health rows point at [id]. Re-running an import creates another batch and
 * does not insert health rows whose natural key is already stored.
 */
@Entity(tableName = "edge_import_batch")
data class EdgeImportBatchRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    val source: String,
    @ColumnInfo(name = "user_version") val userVersion: Int,
    @ColumnInfo(name = "byte_size") val byteSize: Long,
    val sha256: String,
    @ColumnInfo(name = "inserted_rows") val insertedRows: Int,
    @ColumnInfo(name = "already_present") val alreadyPresent: Int,
    @ColumnInfo(name = "skipped_strap_overlap") val skippedStrapOverlap: Int,
)

/**
 * One second from Edge `decoded_onehz`, or a `samples` row that table lacks.
 *
 * [source] is [EDGE_IMPORT_SOURCE]. [edgeSource] is the backup's own `source`
 * column when the row came from `decoded_onehz`. The primary key is the
 * backup's primary key, so a second import does not add another second.
 */
@Entity(
    tableName = "edge_onehz",
    primaryKeys = ["device_id", "ts_ms"],
    indices = [Index(value = ["ts_ms"])],
)
data class EdgeOneHzRow(
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "ts_ms") val tsMs: Long,
    @ColumnInfo(name = "rec_ts") val recTs: Long?,
    val counter: Long?,
    val hr: Long?,
    val ax: Double?,
    val ay: Double?,
    val az: Double?,
    @ColumnInfo(name = "spo2_red_raw") val spo2RedRaw: Long?,
    @ColumnInfo(name = "spo2_ir_raw") val spo2IrRaw: Long?,
    @ColumnInfo(name = "skin_temp_raw") val skinTempRaw: Long?,
    @ColumnInfo(name = "step_count") val stepCount: Long?,
    @ColumnInfo(name = "step_cadence") val stepCadence: Long?,
    @ColumnInfo(name = "activity_class") val activityClass: Long?,
    @ColumnInfo(name = "skin_temp_c") val skinTempC: Double?,
    @ColumnInfo(name = "on_wrist") val onWrist: Long?,
    @ColumnInfo(name = "hr_valid") val hrValid: Long?,
    @ColumnInfo(name = "hr_alt") val hrAlt: Long?,
    @ColumnInfo(name = "ambient_raw") val ambientRaw: Long?,
    @ColumnInfo(name = "device_family") val deviceFamily: String?,
    @ColumnInfo(name = "edge_source") val edgeSource: String?,
    @ColumnInfo(name = "temp_ch2_c") val tempCh2C: Double?,
    @ColumnInfo(name = "temp_ch3_c") val tempCh3C: Double?,
    @ColumnInfo(name = "signal_quality_logvar") val signalQualityLogvar: Double?,
    @ColumnInfo(name = "dyn_accel_g") val dynAccelG: Double?,
    @ColumnInfo(name = "ts_subsec") val tsSubsec: Long?,
    @ColumnInfo(name = "band_sleep_state") val bandSleepState: Long?,
    @ColumnInfo(name = "record_kind") val recordKind: String,
    @ColumnInfo(name = "import_batch_id") val importBatchId: String,
    val source: String,
)

/** One beat interval from Edge `decoded_rr`. [rrMs] is a duration, not an epoch. */
@Entity(
    tableName = "edge_rr",
    primaryKeys = ["device_id", "ts_ms", "beat_index"],
)
data class EdgeRrRow(
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "ts_ms") val tsMs: Long,
    @ColumnInfo(name = "rec_ts") val recTs: Long,
    @ColumnInfo(name = "beat_index") val beatIndex: Long,
    @ColumnInfo(name = "rr_ts_ms") val rrTsMs: Long,
    @ColumnInfo(name = "rr_ms") val rrMs: Long,
    @ColumnInfo(name = "device_family") val deviceFamily: String?,
    @ColumnInfo(name = "edge_source") val edgeSource: String?,
    @ColumnInfo(name = "beat_ts_ms") val beatTsMs: Long?,
    @ColumnInfo(name = "import_batch_id") val importBatchId: String,
    val source: String,
)

/** One Edge `sessions` row. [edgeSource] keeps the backup's source, such as `manual`. */
@Entity(tableName = "edge_workout")
data class EdgeWorkoutRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "start_ts") val startTs: Long,
    @ColumnInfo(name = "end_ts") val endTs: Long?,
    val type: String,
    val status: String,
    val calories: Double?,
    val strain: Double?,
    @ColumnInfo(name = "max_hr") val maxHr: Long?,
    @ColumnInfo(name = "duration_min") val durationMin: Long?,
    @ColumnInfo(name = "zone_min_json") val zoneMinJson: String?,
    val steps: Long?,
    @ColumnInfo(name = "cadence_spm") val cadenceSpm: Long?,
    @ColumnInfo(name = "hrr_bpm") val hrrBpm: Double?,
    @ColumnInfo(name = "edge_source") val edgeSource: String,
    @ColumnInfo(name = "private") val isPrivate: Long,
    @ColumnInfo(name = "device_family") val deviceFamily: String?,
    @ColumnInfo(name = "trace_json") val traceJson: String?,
    @ColumnInfo(name = "trace_samples") val traceSamples: Long?,
    val rpe: Double?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "avg_hr") val avgHr: Long?,
    @ColumnInfo(name = "vo2max_estimate") val vo2maxEstimate: Double?,
    @ColumnInfo(name = "end_ts_fabricated") val endTsFabricated: Long,
    @ColumnInfo(name = "import_batch_id") val importBatchId: String,
    val source: String,
)

/** One Edge `workout_route` point. Coordinates are stored as they appear in the backup. */
@Entity(
    tableName = "edge_workout_point",
    primaryKeys = ["session_id", "seq"],
)
data class EdgeWorkoutPointRow(
    @ColumnInfo(name = "session_id") val sessionId: String,
    val seq: Long,
    @ColumnInfo(name = "ts_ms") val tsMs: Long,
    val lat: Double,
    val lng: Double,
    val alt: Double?,
    val accuracy: Double?,
    val speed: Double?,
    @ColumnInfo(name = "import_batch_id") val importBatchId: String,
    val source: String,
)

/** One Edge `workout_split` row. */
@Entity(
    tableName = "edge_workout_split",
    primaryKeys = ["session_id", "km"],
)
data class EdgeWorkoutSplitRow(
    @ColumnInfo(name = "session_id") val sessionId: String,
    val km: Long,
    val meters: Double,
    @ColumnInfo(name = "duration_sec") val durationSec: Long,
    @ColumnInfo(name = "avg_hr") val avgHr: Double?,
    @ColumnInfo(name = "net_elev_m") val netElevM: Double?,
    @ColumnInfo(name = "import_batch_id") val importBatchId: String,
    val source: String,
)

/** One Edge `strength_set` row. */
@Entity(
    tableName = "edge_strength_set",
    primaryKeys = ["session_id", "seq"],
)
data class EdgeStrengthSetRow(
    @ColumnInfo(name = "session_id") val sessionId: String,
    val seq: Long,
    @ColumnInfo(name = "exercise_key") val exerciseKey: String,
    @ColumnInfo(name = "set_index") val setIndex: Long,
    val reps: Long?,
    @ColumnInfo(name = "load_kg") val loadKg: Double?,
    val rpe: Long?,
    @ColumnInfo(name = "hold_sec") val holdSec: Long?,
    @ColumnInfo(name = "rest_sec") val restSec: Long?,
    @ColumnInfo(name = "at_ts") val atTs: Long?,
    val note: String,
    @ColumnInfo(name = "import_batch_id") val importBatchId: String,
    val source: String,
)

/**
 * One Edge `sleep_session_candidates` row.
 *
 * [payloadJson] is the backup text unchanged. [onsetTs] and [offsetTs] are
 * copied when that JSON contains a numeric `onset_ts`/`onset` or
 * `offset_ts`/`offset`. A missing or non-numeric field stays null.
 */
@Entity(
    tableName = "edge_sleep",
    primaryKeys = ["day_id", "algo_version"],
)
data class EdgeSleepRow(
    @ColumnInfo(name = "day_id") val dayId: String,
    @ColumnInfo(name = "algo_version") val algoVersion: Long,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "computed_at") val computedAt: Long,
    @ColumnInfo(name = "onset_ts") val onsetTs: Long?,
    @ColumnInfo(name = "offset_ts") val offsetTs: Long?,
    @ColumnInfo(name = "import_batch_id") val importBatchId: String,
    val source: String,
)

/** One Edge `day_result` row, including a null readiness scalar when the backup has one. */
@Entity(
    tableName = "edge_day_score",
    primaryKeys = ["day_id", "algo_version"],
)
data class EdgeDayScoreRow(
    @ColumnInfo(name = "day_id") val dayId: String,
    @ColumnInfo(name = "algo_version") val algoVersion: Long,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "window_json") val windowJson: String,
    @ColumnInfo(name = "computed_at") val computedAt: Long,
    val finalized: Long,
    val rhr: Double?,
    val rmssd: Double?,
    val readiness: Double?,
    val skipped: Long,
    val partial: Long,
    @ColumnInfo(name = "import_batch_id") val importBatchId: String,
    val source: String,
)

/** One Edge `metric_series` value. */
@Entity(
    tableName = "edge_metric_series",
    primaryKeys = ["date", "key"],
)
data class EdgeMetricSeriesRow(
    val date: String,
    val key: String,
    val value: Double?,
    @ColumnInfo(name = "import_batch_id") val importBatchId: String,
    val source: String,
)

/** One Edge `metric_method` row. [metricClass] is the backup's `class` column. */
@Entity(
    tableName = "edge_metric_method",
    primaryKeys = ["date", "key"],
)
data class EdgeMetricMethodRow(
    val date: String,
    val key: String,
    val method: String,
    val family: String,
    @ColumnInfo(name = "class") val metricClass: String,
    @ColumnInfo(name = "import_batch_id") val importBatchId: String,
    val source: String,
)

/** One Edge `metric_series_version` row. */
@Entity(tableName = "edge_metric_version")
data class EdgeMetricVersionRow(
    @PrimaryKey val date: String,
    @ColumnInfo(name = "algo_version") val algoVersion: Long,
    @ColumnInfo(name = "edge_source") val edgeSource: String?,
    @ColumnInfo(name = "device_family") val deviceFamily: String?,
    @ColumnInfo(name = "coverage_devices") val coverageDevices: String?,
    @ColumnInfo(name = "priority_hash") val priorityHash: String?,
    @ColumnInfo(name = "import_batch_id") val importBatchId: String,
    val source: String,
)

/** One Edge `wake_day_features` payload, stored as text. */
@Entity(
    tableName = "edge_wake_features",
    primaryKeys = ["day_id", "algo_version"],
)
data class EdgeWakeFeaturesRow(
    @ColumnInfo(name = "day_id") val dayId: String,
    @ColumnInfo(name = "algo_version") val algoVersion: Long,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "computed_at") val computedAt: Long,
    @ColumnInfo(name = "import_batch_id") val importBatchId: String,
    val source: String,
)

/**
 * One Edge `substrate_archive` blob.
 *
 * [blob] is the bytes from the backup, compressed or not. [decoded] is 0.
 * This importer does not inflate the blob or interpret an OSA1 layout.
 * [oneHzRows] and [rrRows] are the backup's declared counts, not imported samples.
 */
@Entity(
    tableName = "edge_substrate_archive",
    primaryKeys = ["device_id", "utc_day"],
)
data class EdgeSubstrateArchiveRow(
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "utc_day") val utcDay: Long,
    val codec: Long,
    @ColumnInfo(name = "from_ts") val fromTs: Long,
    @ColumnInfo(name = "to_ts") val toTs: Long,
    @ColumnInfo(name = "onehz_rows") val oneHzRows: Long,
    @ColumnInfo(name = "rr_rows") val rrRows: Long,
    @ColumnInfo(name = "raw_bytes") val rawBytes: Long,
    val fingerprint: Long,
    @ColumnInfo(name = "tz_offset_min") val tzOffsetMin: Long?,
    val blob: ByteArray,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val decoded: Long,
    @ColumnInfo(name = "import_batch_id") val importBatchId: String,
    val source: String,
)
