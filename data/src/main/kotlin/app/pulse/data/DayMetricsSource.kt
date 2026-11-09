package app.pulse.data

import androidx.room.ColumnInfo
import app.pulse.metrics.DayBook
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
 * Per-day text for the latest stored strap.
 *
 * The text names the time zone and the formulas. It does not include the
 * device address, history tokens, or raw record bytes.
 */
interface DayMetricsSource {
    fun report(zone: ZoneId = ZoneId.systemDefault()): String
}

class RoomDayMetrics(
    private val database: PulseDatabase,
    private val devices: DeviceDirectory,
) : DayMetricsSource {
    override fun report(zone: ZoneId): String {
        return try {
            val device = devices.latest()
            val records = if (device == null) {
                null
            } else {
                database.samples().metricRows(device.address).map { it.toSecondRecord() }
            }
            metricsText(zone, records)
        } catch (_: Exception) {
            "Day metrics could not be read.\n"
        }
    }
}

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
    )
}
