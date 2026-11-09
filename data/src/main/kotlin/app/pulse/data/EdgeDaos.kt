package app.pulse.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EdgeImportDao {
    @Insert
    fun insertBatch(row: EdgeImportBatchRow)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertOneHz(rows: List<EdgeOneHzRow>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertRr(rows: List<EdgeRrRow>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertWorkouts(rows: List<EdgeWorkoutRow>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertPoints(rows: List<EdgeWorkoutPointRow>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertSplits(rows: List<EdgeWorkoutSplitRow>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertSets(rows: List<EdgeStrengthSetRow>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertSleep(rows: List<EdgeSleepRow>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertScores(rows: List<EdgeDayScoreRow>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertMetrics(rows: List<EdgeMetricSeriesRow>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertMethods(rows: List<EdgeMetricMethodRow>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertVersions(rows: List<EdgeMetricVersionRow>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertWake(rows: List<EdgeWakeFeaturesRow>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertArchives(rows: List<EdgeSubstrateArchiveRow>): List<Long>

    @Query("SELECT COUNT(*) FROM edge_import_batch")
    fun batchCount(): Int

    @Query("SELECT COUNT(*) FROM edge_onehz")
    fun oneHzCount(): Int

    @Query("SELECT COUNT(*) FROM edge_rr")
    fun rrCount(): Int

    @Query("SELECT COUNT(*) FROM edge_workout")
    fun workoutCount(): Int

    @Query("SELECT COUNT(*) FROM edge_workout_point")
    fun pointCount(): Int

    @Query("SELECT COUNT(*) FROM edge_workout_split")
    fun splitCount(): Int

    @Query("SELECT COUNT(*) FROM edge_strength_set")
    fun setCount(): Int

    @Query("SELECT COUNT(*) FROM edge_sleep")
    fun sleepCount(): Int

    @Query("SELECT COUNT(*) FROM edge_day_score")
    fun scoreCount(): Int

    @Query("SELECT COUNT(*) FROM edge_metric_series")
    fun metricCount(): Int

    @Query("SELECT COUNT(*) FROM edge_metric_method")
    fun methodCount(): Int

    @Query("SELECT COUNT(*) FROM edge_metric_version")
    fun versionCount(): Int

    @Query("SELECT COUNT(*) FROM edge_wake_features")
    fun wakeCount(): Int

    @Query("SELECT COUNT(*) FROM edge_substrate_archive")
    fun archiveCount(): Int

    @Query("SELECT * FROM edge_import_batch WHERE id = :id")
    fun batch(id: String): EdgeImportBatchRow?

    @Query("SELECT * FROM edge_onehz WHERE device_id = :deviceId AND ts_ms = :tsMs")
    fun oneHz(deviceId: String, tsMs: Long): EdgeOneHzRow?

    @Query(
        "SELECT * FROM edge_rr WHERE device_id = :deviceId AND ts_ms = :tsMs " +
            "AND beat_index = :beatIndex",
    )
    fun rr(deviceId: String, tsMs: Long, beatIndex: Long): EdgeRrRow?

    @Query("SELECT * FROM edge_workout WHERE id = :id")
    fun workout(id: String): EdgeWorkoutRow?

    @Query("SELECT * FROM edge_workout_point WHERE session_id = :sessionId AND seq = :seq")
    fun point(sessionId: String, seq: Long): EdgeWorkoutPointRow?

    @Query("SELECT * FROM edge_workout_split WHERE session_id = :sessionId AND km = :km")
    fun split(sessionId: String, km: Long): EdgeWorkoutSplitRow?

    @Query("SELECT * FROM edge_strength_set WHERE session_id = :sessionId AND seq = :seq")
    fun set(sessionId: String, seq: Long): EdgeStrengthSetRow?

    @Query("SELECT * FROM edge_sleep WHERE day_id = :dayId AND algo_version = :algoVersion")
    fun sleep(dayId: String, algoVersion: Long): EdgeSleepRow?

    @Query("SELECT * FROM edge_day_score WHERE day_id = :dayId AND algo_version = :algoVersion")
    fun score(dayId: String, algoVersion: Long): EdgeDayScoreRow?

    @Query("SELECT * FROM edge_metric_series WHERE date = :date AND `key` = :key")
    fun metric(date: String, key: String): EdgeMetricSeriesRow?

    @Query("SELECT * FROM edge_metric_method WHERE date = :date AND `key` = :key")
    fun method(date: String, key: String): EdgeMetricMethodRow?

    @Query("SELECT * FROM edge_metric_version WHERE date = :date")
    fun version(date: String): EdgeMetricVersionRow?

    @Query("SELECT * FROM edge_wake_features WHERE day_id = :dayId AND algo_version = :algoVersion")
    fun wake(dayId: String, algoVersion: Long): EdgeWakeFeaturesRow?

    @Query("SELECT * FROM edge_substrate_archive WHERE device_id = :deviceId AND utc_day = :utcDay")
    fun archive(deviceId: String, utcDay: Long): EdgeSubstrateArchiveRow?

    @Query("SELECT COUNT(*) FROM edge_onehz WHERE source != :source")
    fun oneHzWithOtherSource(source: String): Int

    @Query("SELECT DISTINCT unix_s FROM r18_sample")
    fun strapSeconds(): List<Long>

    @Query(
        "SELECT heart_rate_bpm, heart_rate_raw FROM r18_sample WHERE unix_s = :unixSeconds " +
            "ORDER BY record_index LIMIT 1",
    )
    fun strapHeartRate(unixSeconds: Long): StrapHeartRate?

    @Query(
        "SELECT ts_ms, hr FROM edge_onehz WHERE ts_ms >= :startMs AND ts_ms < :endMs " +
            "ORDER BY ts_ms LIMIT 1",
    )
    fun edgeHeartRate(startMs: Long, endMs: Long): EdgeHeartRateHit?

    @Query(
        "SELECT ts_ms, hr, hr_valid, activity_class, step_count, band_sleep_state " +
            "FROM edge_onehz WHERE source = :source ORDER BY ts_ms ASC",
    )
    fun metricOneHz(source: String): List<EdgeOneHzMetricRow>

    @Query(
        "SELECT ts_ms, rr_ms FROM edge_rr WHERE source = :source " +
            "ORDER BY ts_ms ASC, beat_index ASC",
    )
    fun metricRr(source: String): List<EdgeRrMetricRow>

    @Query(
        "SELECT day_id, algo_version, onset_ts, offset_ts FROM edge_sleep " +
            "WHERE source = :source AND onset_ts IS NOT NULL AND offset_ts IS NOT NULL",
    )
    fun metricSleep(source: String): List<EdgeSleepMetricRow>

    @Query(
        "SELECT day_id, algo_version, rhr, rmssd, readiness FROM edge_day_score WHERE source = :source",
    )
    fun metricScores(source: String): List<EdgeScoreMetricRow>
}

data class StrapHeartRate(
    @ColumnInfo(name = "heart_rate_bpm") val bpm: Int?,
    @ColumnInfo(name = "heart_rate_raw") val raw: Int,
)

data class EdgeHeartRateHit(
    @ColumnInfo(name = "ts_ms") val tsMs: Long,
    val hr: Long?,
)

/**
 * Heart rate for one Unix second.
 *
 * A layout-18 row at that second wins, including when the Edge row was
 * imported earlier. Edge rows whose second is already on the strap are not
 * inserted; this lookup still prefers the strap if a later sync covers a
 * second that was imported first.
 */
fun PulseDatabase.heartRateAt(unixSeconds: Long): HeartRateAt? {
    val strap = edgeImport().strapHeartRate(unixSeconds)
    if (strap != null) {
        return HeartRateAt(
            unixSeconds = unixSeconds,
            bpm = (strap.bpm ?: strap.raw).toLong(),
            source = STRAP_SAMPLE_SOURCE,
        )
    }
    val edge = edgeImport().edgeHeartRate(unixSeconds * 1000, unixSeconds * 1000 + 1000)
        ?: return null
    return HeartRateAt(
        unixSeconds = unixSeconds,
        bpm = edge.hr,
        source = EDGE_IMPORT_SOURCE,
    )
}
