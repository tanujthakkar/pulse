package app.pulse.data

import androidx.room.ColumnInfo
import app.pulse.metrics.DayBook
import app.pulse.metrics.EdgeDayNote
import app.pulse.metrics.EdgeSleepWindow
import app.pulse.metrics.SOURCE_EDGE
import app.pulse.metrics.SOURCE_STRAP
import app.pulse.metrics.SecondRecord
import app.pulse.metrics.computeDays
import app.pulse.metrics.dayBookText
import java.time.ZoneId

/**
 * Columns the day screen reads from one stored layout-18 second.
 *
 * Raw companions, including byte 82 and the alternate heart-rate byte, are
 * not selected.
 */
data class R18MetricRow(
    @ColumnInfo(name = "unix_s") val unixSeconds: Long,
    @ColumnInfo(name = "record_index") val recordIndex: Long,
    @ColumnInfo(name = "heart_rate_bpm") val heartRateBpm: Int?,
    @ColumnInfo(name = "rr_count") val rrCount: Int,
    @ColumnInfo(name = "rr_tick_0") val rrTick0: Int?,
    @ColumnInfo(name = "rr_tick_1") val rrTick1: Int?,
    @ColumnInfo(name = "rr_tick_2") val rrTick2: Int?,
    @ColumnInfo(name = "rr_tick_3") val rrTick3: Int?,
    @ColumnInfo(name = "activity_class") val activityClass: Int?,
    @ColumnInfo(name = "rest_state") val restState: Int?,
    @ColumnInfo(name = "step_counter") val stepCounter: Int,
)

/**
 * One read of the day book plus the strap facts the home screen shows beside it.
 *
 * [batteryPercent] prefers the standard battery service, then command 26.
 * [lastSyncUnixSeconds] is the newest history session's end, or its start
 * when the session is still open. Neither field starts a strap command.
 */
data class DaySnapshot(
    val book: DayBook,
    val batteryPercent: Int?,
    val lastSyncUnixSeconds: Long?,
)

interface DayMetricsSource {
    fun report(zone: ZoneId = ZoneId.systemDefault()): String

    fun snapshot(zone: ZoneId = ZoneId.systemDefault()): DaySnapshot =
        DaySnapshot(DayBook(zone.id, emptyList(), null), null, null)
}

class RoomDayMetrics(
    private val database: PulseDatabase,
    private val devices: DeviceDirectory,
) : DayMetricsSource {
    override fun report(zone: ZoneId): String {
        val loaded = read(zone)
        if (loaded.failed) return "Day metrics could not be read.\n"
        return dayBookText(loaded.snapshot.book)
    }

    override fun snapshot(zone: ZoneId): DaySnapshot = read(zone).snapshot

    private fun read(zone: ZoneId): DayRead {
        return try {
            val device = devices.latest()
            val strap = if (device == null) {
                emptyList()
            } else {
                database.samples().metricRows(device.address).map { it.toSecondRecord() }
            }
            val imported = database.edgeImport()
            val inputs = StoredDayInputs(
                oneHz = imported.metricOneHz(EDGE_IMPORT_SOURCE),
                rr = imported.metricRr(EDGE_IMPORT_SOURCE),
                sleep = imported.metricSleep(EDGE_IMPORT_SOURCE),
                scores = imported.metricScores(EDGE_IMPORT_SOURCE),
            )
            val book = if (device == null && inputs.oneHz.isEmpty()) {
                DayBook(zone.id, emptyList(), "No device row is stored.")
            } else {
                val assembly = assembleDays(strap, inputs)
                computeDays(assembly.records, zone, assembly.windows, assembly.notes)
            }
            val sync = database.sessions().latestActivityUnixSeconds()
            DayRead(DaySnapshot(book, batteryPercent(device), sync), failed = false)
        } catch (_: Exception) {
            DayRead(
                DaySnapshot(DayBook(zone.id, emptyList(), "Day metrics could not be read."), null, null),
                failed = true,
            )
        }
    }
}

private data class DayRead(val snapshot: DaySnapshot, val failed: Boolean)

private fun batteryPercent(device: DeviceRow?): Int? {
    device ?: return null
    device.serviceBatteryPercent?.let { return it }
    return device.commandBatteryPercent?.toInt()
}

internal data class StoredDayInputs(
    val oneHz: List<EdgeOneHzMetricRow> = emptyList(),
    val rr: List<EdgeRrMetricRow> = emptyList(),
    val sleep: List<EdgeSleepMetricRow> = emptyList(),
    val scores: List<EdgeScoreMetricRow> = emptyList(),
)

internal data class DayAssembly(
    val records: List<SecondRecord>,
    val windows: List<EdgeSleepWindow>,
    val notes: List<EdgeDayNote>,
)

/**
 * Strap seconds block Edge seconds at the same Unix time.
 *
 * That is the same preference as [PulseDatabase.heartRateAt]. Edge R-R
 * intervals attach only to an Edge second that was kept.
 */
internal fun assembleDays(strap: List<SecondRecord>, inputs: StoredDayInputs): DayAssembly {
    val blocked = strap.map { it.unixSeconds }.toHashSet()
    val edge = edgeSeconds(inputs.oneHz, inputs.rr).filter { it.unixSeconds !in blocked }
    return DayAssembly(
        records = strap + edge,
        windows = sleepWindows(inputs.sleep),
        notes = inputs.scores.map {
            EdgeDayNote(
                dayId = it.dayId,
                algoVersion = it.algoVersion,
                restingHr = it.rhr,
                rmssd = it.rmssd,
                readiness = it.readiness,
            )
        },
    )
}

internal fun edgeSeconds(
    oneHz: List<EdgeOneHzMetricRow>,
    rr: List<EdgeRrMetricRow>,
): List<SecondRecord> {
    val beats = HashMap<Long, MutableList<Int>>()
    for (row in rr) {
        if (row.rrMs <= 0L || row.rrMs > Int.MAX_VALUE) continue
        val second = Math.floorDiv(row.tsMs, 1000L)
        beats.getOrPut(second) { ArrayList() }.add(row.rrMs.toInt())
    }
    val chosen = LinkedHashMap<Long, EdgeOneHzMetricRow>()
    for (row in oneHz.sortedBy { it.tsMs }) {
        val second = Math.floorDiv(row.tsMs, 1000L)
        if (second !in chosen) chosen[second] = row
    }
    return chosen.map { (second, row) ->
        val stepsKnown = row.stepCount != null && row.stepCount in 0L..Int.MAX_VALUE.toLong()
        SecondRecord(
            unixSeconds = second,
            recordIndex = 0,
            heartRateBpm = edgeHeartBpm(row.hr, row.hrValid),
            rrMillis = beats[second].orEmpty(),
            activityClass = edgeActivity(row.activityClass),
            bandMotion = edgeBandMotion(row.bandSleepState),
            stepCounter = if (stepsKnown) row.stepCount!!.toInt() else 0,
            source = SOURCE_EDGE,
            stepsKnown = stepsKnown,
        )
    }
}

internal fun sleepWindows(rows: List<EdgeSleepMetricRow>): List<EdgeSleepWindow> {
    return rows
        .mapNotNull { row ->
            val onset = row.onsetTs ?: return@mapNotNull null
            val offset = row.offsetTs ?: return@mapNotNull null
            val start = epochSeconds(onset)
            val end = epochSeconds(offset)
            if (end <= start) return@mapNotNull null
            EdgeSleepWindow(row.dayId, row.algoVersion, start, end)
        }
        .groupBy { it.dayId }
        .values
        .map { windows -> windows.maxBy { it.algoVersion } }
}

/** Epoch milliseconds are stored by some payloads; values below 1e10 are seconds. */
internal fun epochSeconds(value: Long): Long = if (value >= 10_000_000_000L) value / 1000 else value

/** Layout-18 display window is 25..230. A zero hr_valid drops the sample. */
internal fun edgeHeartBpm(hr: Long?, hrValid: Long?): Int? {
    if (hrValid == 0L || hr == null || hr !in 25L..230L) return null
    return hr.toInt()
}

/** Edge band_sleep_state uses the same 0..3 labels as strap band motion. */
internal fun edgeBandMotion(state: Long?): Int? = if (state != null && state in 0L..3L) state.toInt() else null

internal fun edgeActivity(value: Long?): Int? = if (value != null && value in 0L..2L) value.toInt() else null

internal fun metricsText(zone: ZoneId, records: List<SecondRecord>?): String {
    if (records == null) {
        return dayBookText(DayBook(zone.id, emptyList(), "No device row is stored."))
    }
    return dayBookText(computeDays(records, zone))
}

/** Spec §9: `(ticks * 1000 + 512) / 1024` for a positive tick. */
internal fun rrTickToMillis(tick: Int): Int = ((tick.toLong() * 1000L + 512L) / 1024L).toInt()

internal fun R18MetricRow.toSecondRecord(): SecondRecord {
    val ticks = listOf(rrTick0, rrTick1, rrTick2, rrTick3)
    val limit = rrCount.coerceIn(0, ticks.size)
    val intervals = ArrayList<Int>(limit)
    for (index in 0 until limit) {
        val tick = ticks[index] ?: continue
        if (tick <= 0) continue
        val millis = rrTickToMillis(tick)
        if (millis > 0) intervals += millis
    }
    return SecondRecord(
        unixSeconds = unixSeconds,
        recordIndex = recordIndex,
        heartRateBpm = heartRateBpm,
        rrMillis = intervals,
        activityClass = activityClass,
        bandMotion = restState,
        stepCounter = stepCounter,
        source = SOURCE_STRAP,
        stepsKnown = true,
    )
}

data class EdgeOneHzMetricRow(
    @ColumnInfo(name = "ts_ms") val tsMs: Long,
    val hr: Long?,
    @ColumnInfo(name = "hr_valid") val hrValid: Long?,
    @ColumnInfo(name = "activity_class") val activityClass: Long?,
    @ColumnInfo(name = "step_count") val stepCount: Long?,
    @ColumnInfo(name = "band_sleep_state") val bandSleepState: Long?,
)

data class EdgeRrMetricRow(
    @ColumnInfo(name = "ts_ms") val tsMs: Long,
    @ColumnInfo(name = "rr_ms") val rrMs: Long,
)

data class EdgeSleepMetricRow(
    @ColumnInfo(name = "day_id") val dayId: String,
    @ColumnInfo(name = "algo_version") val algoVersion: Long,
    @ColumnInfo(name = "onset_ts") val onsetTs: Long?,
    @ColumnInfo(name = "offset_ts") val offsetTs: Long?,
)

data class EdgeScoreMetricRow(
    @ColumnInfo(name = "day_id") val dayId: String,
    @ColumnInfo(name = "algo_version") val algoVersion: Long,
    val rhr: Double?,
    val rmssd: Double?,
    val readiness: Double?,
)
