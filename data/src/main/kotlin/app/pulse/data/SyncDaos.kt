package app.pulse.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncSessionDao {
    @Insert
    fun insert(row: SyncSessionRow): Long

    @Query("SELECT * FROM sync_session WHERE id = :id")
    fun find(id: Long): SyncSessionRow?

    @Query("SELECT COUNT(*) FROM sync_session")
    fun count(): Int

    @Query(
        "UPDATE sync_session SET ended_unix_seconds = :endedUnixSeconds, " +
            "ended_subsecond_ticks = :endedSubsecondTicks WHERE id = :id",
    )
    fun close(id: Long, endedUnixSeconds: Long, endedSubsecondTicks: Int?)
}

@Dao
interface SyncChunkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(row: SyncChunkRow): Long

    @Query("SELECT * FROM sync_chunk WHERE device_id = :deviceId AND token = :token LIMIT 1")
    fun find(deviceId: String, token: ByteArray): SyncChunkRow?

    @Query("SELECT COUNT(*) FROM sync_chunk")
    fun count(): Int

    @Query(
        "UPDATE sync_chunk SET acked_at = :ackedAt " +
            "WHERE device_id = (SELECT device_id FROM sync_session WHERE id = :sessionId) " +
            "AND token = :token",
    )
    fun markAcked(sessionId: Long, token: ByteArray, ackedAt: Long): Int
}

@Dao
interface RawFrameDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(row: RawFrameRow): Long

    @Query("SELECT COUNT(*) FROM raw_frame")
    fun count(): Int

    @Query("SELECT * FROM raw_frame WHERE device_id = :deviceId AND sha256 = :sha256 LIMIT 1")
    fun find(deviceId: String, sha256: ByteArray): RawFrameRow?
}

@Dao
interface R18SampleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(row: R18SampleRow): Long

    @Query("SELECT COUNT(*) FROM r18_sample")
    fun count(): Int

    @Query(
        "SELECT * FROM r18_sample WHERE device_id = :deviceId " +
            "AND record_index = :recordIndex AND unix_s = :unixSeconds",
    )
    fun find(deviceId: String, recordIndex: Long, unixSeconds: Long): R18SampleRow?
}

@Dao
interface DeviceBatteryDao {
    @Insert
    fun insert(row: DeviceBatteryRow): Long

    @Query("SELECT COUNT(*) FROM device_battery")
    fun count(): Int

    @Query("SELECT * FROM device_battery WHERE id = :id")
    fun find(id: Long): DeviceBatteryRow?
}
