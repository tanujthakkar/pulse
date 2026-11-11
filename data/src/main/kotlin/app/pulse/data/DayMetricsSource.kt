package app.pulse.data

import androidx.room.ColumnInfo
import androidx.room.InvalidationTracker
import app.pulse.metrics.DayBook
import app.pulse.metrics.DayResult
import app.pulse.metrics.EdgeDayNote
import app.pulse.metrics.EdgeSleepWindow
import app.pulse.metrics.SOURCE_EDGE
import app.pulse.metrics.SOURCE_STRAP
import app.pulse.metrics.SecondRecord
import app.pulse.metrics.DayRules
import app.pulse.metrics.baselineRhythms
import app.pulse.metrics.computeDays
import app.pulse.metrics.datesToRecompute
import app.pulse.metrics.dayBookText
import app.pulse.metrics.daysByWindow
import app.pulse.metrics.prepareWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

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
    @ColumnInfo(name = "skin_temp_c") val skinTempC: Float? = null,
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

    /**
     * Calls [onChange] after a commit to the tables home reads.
     *
     * The callback is not invoked for the current rows. A sync that inserts
     * layout-18 samples fires it when that transaction commits. The returned
     * function removes the observer.
     */
    fun watch(onChange: () -> Unit): () -> Unit = {}

    /** Strap and Edge seconds in [startUnix, endUnix), with strap winning a one-second overlap. */
    fun windowRecords(startUnix: Long, endUnix: Long): List<SecondRecord> = emptyList()

    /**
     * The last book [snapshot] finished, without reading the store again.
     *
     * Activity detail uses this for a resting heart rate it already has.
     * A miss stays on the activity window instead of computing every day.
     */
    fun cachedSnapshot(): DaySnapshot? = null
}

class RoomDayMetrics(
    private val database: PulseDatabase,
    private val devices: DeviceDirectory,
) : DayMetricsSource {
    private val changes = AtomicInteger()
    private val gate = Any()
    private var cached: DaySnapshot? = null
    private var cachedStamp = -1
    private var cachedZone: String? = null
    private var cachedDays: List<DayResult> = emptyList()
    private var cachedStrapMin: Long? = null

    override fun report(zone: ZoneId): String = dayBookText(snapshot(zone).book)

    override fun snapshot(zone: ZoneId): DaySnapshot {
        val stamp = changes.get()
        synchronized(gate) {
            val hit = cached
            if (hit != null && cachedStamp == stamp && cachedZone == zone.id) return hit
        }
        val built = try {
            build(zone)
        } catch (_: Exception) {
            unread(zone)
        } catch (_: OutOfMemoryError) {
            unread(zone)
        }
        val failed = built.book.emptyReason == "Day metrics could not be read."
        synchronized(gate) {
            cached = built
            cachedStamp = if (failed) -1 else stamp
            cachedZone = if (failed) cachedZone else zone.id
        }
        return built
    }

    override fun cachedSnapshot(): DaySnapshot? = synchronized(gate) { cached }

    override fun watch(onChange: () -> Unit): () -> Unit {
        val observer = object : InvalidationTracker.Observer(
            "r18_sample",
            "sync_session",
            "device_battery",
            "edge_onehz",
            "edge_rr",
            "edge_sleep",
            "edge_day_score",
            "manual_activity",
        ) {
            override fun onInvalidated(tables: Set<String>) {
                changes.incrementAndGet()
                onChange()
            }
        }
        database.invalidationTracker.addObserver(observer)
        return { database.invalidationTracker.removeObserver(observer) }
    }

    override fun windowRecords(startUnix: Long, endUnix: Long): List<SecondRecord> {
        return try {
            val pad = DayRules.STRAP_ALIGN_SECONDS
            val queryStart = startUnix - pad
            val queryEnd = endUnix + pad
            val device = devices.latest()
            val strap = if (device == null) {
                emptyList()
            } else {
                database.samples().metricRowsBetween(device.address, queryStart, queryEnd).map { it.toSecondRecord() }
            }
            val imported = database.edgeImport()
            val inputs = StoredDayInputs(
                oneHz = imported.metricOneHzBetween(EDGE_IMPORT_SOURCE, queryStart * 1000L, queryEnd * 1000L),
                rr = imported.metricRrBetween(EDGE_IMPORT_SOURCE, queryStart * 1000L, queryEnd * 1000L),
                sleep = imported.metricSleep(EDGE_IMPORT_SOURCE),
            )
            val assembly = assembleDays(strap, inputs)
            prepareWindow(assembly.records, assembly.windows, startUnix, endUnix)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Reads each local day from its own window and keeps earlier days.
     *
     * A history sync only appends, so a day before yesterday does not change
     * when today's samples arrive. The window is dropped after that day is
     * built. Nothing here holds every stored second at once.
     */
    private fun build(zone: ZoneId): DaySnapshot {
        val device = devices.latest()
        val samples = database.samples()
        val strapMin = device?.let { samples.minUnix(it.address) }
        val strapMax = device?.let { samples.maxUnix(it.address) }
        val imported = database.edgeImport()
        val sleep = imported.metricSleep(EDGE_IMPORT_SOURCE)
        val scores = imported.metricScores(EDGE_IMPORT_SOURCE)
        val notes = scores.map {
            EdgeDayNote(
                dayId = it.dayId,
                algoVersion = it.algoVersion,
                restingHr = it.rhr,
                rmssd = it.rmssd,
                readiness = it.readiness,
            )
        }
        val windows = sleepWindows(sleep)
        if (device == null && strapMin == null && imported.minOneHz(EDGE_IMPORT_SOURCE) == null &&
            sleep.isEmpty() && scores.isEmpty()
        ) {
            cachedDays = emptyList()
            cachedStrapMin = null
            return DaySnapshot(
                DayBook(zone.id, emptyList(), "No device row is stored."),
                batteryPercent(device),
                database.sessions().latestActivityUnixSeconds(),
            )
        }
        val dates = coveringDates(
            strapMin = strapMin,
            strapMax = strapMax,
            edgeMinMs = imported.minOneHz(EDGE_IMPORT_SOURCE),
            edgeMaxMs = imported.maxOneHz(EDGE_IMPORT_SOURCE),
            zone = zone,
            scoreDayIds = scores.map { it.dayId },
        )
        val keep = if (cachedStrapMin == strapMin && cachedZone == zone.id) cachedDays else emptyList()
        val keptDates = keep.map { it.date }.toSet()
        val freshDates = datesToRecompute(dates, keptDates)
        val kept = keep.filter { it.date !in freshDates }
        val fresh = daysByWindow(
            zone = zone,
            dates = freshDates,
            loadWindow = { start, end ->
                val strap = if (device == null) {
                    emptyList()
                } else {
                    samples.metricRowsBetween(device.address, start, end).map { it.toSecondRecord() }
                }
                val assembly = assembleDays(
                    strap,
                    StoredDayInputs(
                        oneHz = imported.metricOneHzBetween(EDGE_IMPORT_SOURCE, start * 1000L, end * 1000L),
                        rr = imported.metricRrBetween(EDGE_IMPORT_SOURCE, start * 1000L, end * 1000L),
                        sleep = sleep,
                        scores = scores,
                    ),
                )
                assembly.records
            },
            priorRhythms = baselineRhythms(kept),
            edgeWindows = windows,
            edgeScores = notes,
        )
        val days = (kept + fresh).sortedByDescending { it.date }
        cachedDays = days
        cachedStrapMin = strapMin
        val book = if (days.isEmpty()) {
            DayBook(zone.id, emptyList(), "No layout-18 or Edge one-second rows are stored.")
        } else {
            DayBook(zone.id, days, null)
        }
        return DaySnapshot(book, batteryPercent(device), database.sessions().latestActivityUnixSeconds())
    }
}

private fun unread(zone: ZoneId): DaySnapshot =
    DaySnapshot(DayBook(zone.id, emptyList(), "Day metrics could not be read."), null, null)

/**
 * Local dates that have strap seconds, Edge seconds, or an Edge day score.
 *
 * The bounds are aggregates. This does not load the samples.
 */
internal fun coveringDates(
    strapMin: Long?,
    strapMax: Long?,
    edgeMinMs: Long?,
    edgeMaxMs: Long?,
    zone: ZoneId,
    scoreDayIds: List<String> = emptyList(),
): List<LocalDate> {
    var min = strapMin
    var max = strapMax
    val edgeMin = edgeMinMs?.let { epochSeconds(it) }
    val edgeMax = edgeMaxMs?.let { epochSeconds(it) }
    if (edgeMin != null) min = if (min == null) edgeMin else minOf(min, edgeMin)
    if (edgeMax != null) max = if (max == null) edgeMax else maxOf(max, edgeMax)
    val dates = LinkedHashSet<LocalDate>()
    if (min != null && max != null && max >= min) {
        var date = Instant.ofEpochSecond(min).atZone(zone).toLocalDate()
        val last = Instant.ofEpochSecond(max).atZone(zone).toLocalDate()
        var guard = 0
        while (!date.isAfter(last) && guard < 4_000) {
            dates += date
            date = date.plusDays(1)
            guard += 1
        }
    }
    for (id in scoreDayIds) {
        val parsed = try {
            LocalDate.parse(id)
        } catch (_: Exception) {
            null
        }
        if (parsed != null) dates += parsed
    }
    return dates.sorted()
}

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
            skinTempC = row.skinTempC?.toFloat()?.takeIf { it in 5f..45f },
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
        skinTempC = skinTempC?.takeIf { it in 5f..45f },
    )
}

data class EdgeOneHzMetricRow(
    @ColumnInfo(name = "ts_ms") val tsMs: Long,
    val hr: Long?,
    @ColumnInfo(name = "hr_valid") val hrValid: Long?,
    @ColumnInfo(name = "activity_class") val activityClass: Long?,
    @ColumnInfo(name = "step_count") val stepCount: Long?,
    @ColumnInfo(name = "band_sleep_state") val bandSleepState: Long?,
    @ColumnInfo(name = "skin_temp_c") val skinTempC: Double? = null,
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
