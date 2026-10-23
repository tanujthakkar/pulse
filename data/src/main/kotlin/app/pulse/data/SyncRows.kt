package app.pulse.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One history attempt for a strap.
 *
 * Times are Unix seconds UTC plus the strap's 1/32768 s fraction when the
 * clock read included it. [rangeSnapshot] is the 65-byte data-range body
 * stored when the attempt opened. It is null only for a session written
 * before that column existed.
 */
@Entity(
    tableName = "sync_session",
    foreignKeys = [
        ForeignKey(
            entity = DeviceRow::class,
            parentColumns = ["address"],
            childColumns = ["device_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["device_id"]),
    ],
)
data class SyncSessionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "started_unix_seconds") val startedUnixSeconds: Long,
    @ColumnInfo(name = "started_subsecond_ticks") val startedSubsecondTicks: Int?,
    @ColumnInfo(name = "ended_unix_seconds") val endedUnixSeconds: Long?,
    @ColumnInfo(name = "ended_subsecond_ticks") val endedSubsecondTicks: Int?,
    @ColumnInfo(name = "range_snapshot") val rangeSnapshot: ByteArray?,
)

/**
 * One history chunk inside a session.
 *
 * [token] is the original 8-byte end token. [ackedAt] is Unix seconds UTC when
 * an acknowledgement has been handed to the link. [SyncStore.commitChunk]
 * leaves it null. [SyncStore.markAcknowledged] writes it after that handoff.
 */
@Entity(
    tableName = "sync_chunk",
    foreignKeys = [
        ForeignKey(
            entity = SyncSessionRow::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DeviceRow::class,
            parentColumns = ["address"],
            childColumns = ["device_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["device_id", "token"], unique = true),
        Index(value = ["session_id"]),
    ],
)
data class SyncChunkRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "device_id") val deviceId: String,
    val token: ByteArray,
    @ColumnInfo(name = "frame_count") val frameCount: Int,
    @ColumnInfo(name = "sample_count") val sampleCount: Int,
    @ColumnInfo(name = "archived_count") val archivedCount: Int,
    @ColumnInfo(name = "stored_unix_seconds") val storedUnixSeconds: Long,
    @ColumnInfo(name = "stored_subsecond_ticks") val storedSubsecondTicks: Int?,
    @ColumnInfo(name = "acked_at") val ackedAt: Long?,
)

/**
 * One stored frame, addressed by the SHA-256 of its bytes.
 *
 * The same bytes for one strap are stored once. [archived] is true when the
 * bytes arrived in the archived list (unknown layouts and other raw records).
 */
@Entity(
    tableName = "raw_frame",
    foreignKeys = [
        ForeignKey(
            entity = DeviceRow::class,
            parentColumns = ["address"],
            childColumns = ["device_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SyncChunkRow::class,
            parentColumns = ["id"],
            childColumns = ["chunk_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["device_id", "sha256"], unique = true),
        Index(value = ["chunk_id"]),
    ],
)
data class RawFrameRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "chunk_id") val chunkId: Long,
    val sha256: ByteArray,
    @ColumnInfo(name = "packet_type") val packetType: Int?,
    val layout: Int?,
    @ColumnInfo(name = "record_index") val recordIndex: Long?,
    @ColumnInfo(name = "unix_seconds") val unixSeconds: Long?,
    @ColumnInfo(name = "subsecond_ticks") val subsecondTicks: Int?,
    val archived: Boolean,
    val payload: ByteArray,
)

/**
 * One layout-18 sample.
 *
 * The primary key is `(device_id, record_index, unix_s)`. The record index at
 * frame offset 11 is not a timestamp. [unixSeconds] is Unix seconds UTC.
 * Typed columns sit beside the raw companions from spec §9. [raw] is the
 * record bytes the caller supplied. [decoderVersion] is the decoder that
 * produced the row. Rows written before that column existed migrate as 0.
 */
@Entity(
    tableName = "r18_sample",
    primaryKeys = ["device_id", "record_index", "unix_s"],
    foreignKeys = [
        ForeignKey(
            entity = DeviceRow::class,
            parentColumns = ["address"],
            childColumns = ["device_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SyncChunkRow::class,
            parentColumns = ["id"],
            childColumns = ["chunk_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chunk_id"]),
    ],
)
data class R18SampleRow(
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "record_index") val recordIndex: Long,
    @ColumnInfo(name = "chunk_id") val chunkId: Long,
    @ColumnInfo(name = "unix_s") val unixSeconds: Long,
    @ColumnInfo(name = "subsecond_ticks") val subsecondTicks: Int?,
    @ColumnInfo(name = "heart_rate_bpm") val heartRateBpm: Int?,
    @ColumnInfo(name = "heart_rate_raw") val heartRateRaw: Int,
    @ColumnInfo(name = "rr_count") val rrCount: Int,
    @ColumnInfo(name = "rr_tick_0") val rrTick0: Int?,
    @ColumnInfo(name = "rr_tick_1") val rrTick1: Int?,
    @ColumnInfo(name = "rr_tick_2") val rrTick2: Int?,
    @ColumnInfo(name = "rr_tick_3") val rrTick3: Int?,
    @ColumnInfo(name = "rr_block_raw") val rrBlockRaw: ByteArray,
    @ColumnInfo(name = "cardiac_raw") val cardiacRaw: Int,
    @ColumnInfo(name = "quality_raw") val qualityRaw: Int,
    @ColumnInfo(name = "alternate_hr_raw") val alternateHrRaw: Int,
    @ColumnInfo(name = "packed_word_raw") val packedWordRaw: Int,
    @ColumnInfo(name = "dynamic_accel") val dynamicAccel: Float?,
    @ColumnInfo(name = "dynamic_accel_bits") val dynamicAccelBits: Int,
    @ColumnInfo(name = "axis_accel_0") val axisAccel0: Float?,
    @ColumnInfo(name = "axis_accel_1") val axisAccel1: Float?,
    @ColumnInfo(name = "axis_accel_2") val axisAccel2: Float?,
    @ColumnInfo(name = "axis_accel_0_bits") val axisAccel0Bits: Int,
    @ColumnInfo(name = "axis_accel_1_bits") val axisAccel1Bits: Int,
    @ColumnInfo(name = "axis_accel_2_bits") val axisAccel2Bits: Int,
    @ColumnInfo(name = "step_counter") val stepCounter: Int,
    @ColumnInfo(name = "cadence_raw") val cadenceRaw: Int,
    @ColumnInfo(name = "hardware_counter") val hardwareCounter: Int,
    @ColumnInfo(name = "activity_class") val activityClass: Int?,
    @ColumnInfo(name = "activity_class_raw") val activityClassRaw: Int,
    @ColumnInfo(name = "counter_override_raw") val counterOverrideRaw: Int,
    @ColumnInfo(name = "aux_temp_0_c") val auxTemp0C: Float?,
    @ColumnInfo(name = "aux_temp_1_c") val auxTemp1C: Float?,
    @ColumnInfo(name = "aux_temp_0_raw") val auxTemp0Raw: Int,
    @ColumnInfo(name = "aux_temp_1_raw") val auxTemp1Raw: Int,
    @ColumnInfo(name = "skin_temp_c") val skinTempC: Float?,
    @ColumnInfo(name = "skin_temp_raw") val skinTempRaw: Int,
    @ColumnInfo(name = "status_0_raw") val status0Raw: Int,
    @ColumnInfo(name = "status_1_raw") val status1Raw: Int,
    @ColumnInfo(name = "status_2_raw") val status2Raw: Int,
    @ColumnInfo(name = "motion_raw") val motionRaw: Int,
    @ColumnInfo(name = "rest_state") val restState: Int?,
    @ColumnInfo(name = "sleep_adjacent_raw") val sleepAdjacentRaw: Int,
    val crc32: Long,
    val raw: ByteArray,
    @ColumnInfo(name = "decoder_version", defaultValue = "0") val decoderVersion: Int,
)

/**
 * One battery observation that is not the short device summary.
 *
 * The device row still holds the latest hello and command-26 values. This
 * table is the time series. Nothing sends it off the phone.
 */
@Entity(
    tableName = "device_battery",
    foreignKeys = [
        ForeignKey(
            entity = DeviceRow::class,
            parentColumns = ["address"],
            childColumns = ["device_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["device_id"]),
    ],
)
data class DeviceBatteryRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "unix_seconds") val unixSeconds: Long,
    @ColumnInfo(name = "subsecond_ticks") val subsecondTicks: Int?,
    val source: String,
    val percent: Long?,
    @ColumnInfo(name = "percent_raw") val percentRaw: Long?,
    val millivolts: Int?,
    val charging: Boolean?,
    val raw: ByteArray?,
)

/** A battery observation to append. [source] is a short label such as `command26`. */
data class DeviceBatteryDraft(
    val deviceAddress: String,
    val unixSeconds: Long,
    val subsecondTicks: Int? = null,
    val source: String,
    val percent: Long? = null,
    val percentRaw: Long? = null,
    val millivolts: Int? = null,
    val charging: Boolean? = null,
    val raw: ByteArray? = null,
)
