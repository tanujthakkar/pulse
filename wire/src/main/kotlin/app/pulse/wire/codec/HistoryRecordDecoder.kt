package app.pulse.wire.codec

/**
 * Layout-18 biometric summary from a reassembled format-1 frame.
 *
 * Offsets are absolute frame offsets from spec §9. The record is 124 bytes
 * and the CRC-32 at offset 120 covers `[8, 120)`. Other layouts are returned
 * whole. This type does not start a history sync or send an acknowledgement.
 *
 * [Layout18Reading] is the display model. Alternate heart rate (byte 37) and
 * the sleep-adjacent byte (byte 82) are storage fields on [Layout18Stored]
 * only. Spec §9 and project rules 14–16.
 */
object HistoryRecordDecoder {
    /** Field map implemented by this decoder. Stamped on each layout-18 result. */
    const val VERSION: Int = 1

    const val RECORD_BYTES: Int = 124
    const val LAYOUT: Int = 18

    private const val LAYOUT_OFFSET: Int = 9
    private const val CRC_OFFSET: Int = 120
    private const val RR_OFFSET: Int = 24
    private const val RR_WORDS: Int = 4
    private const val HR_MIN: Int = 25
    private const val HR_MAX: Int = 230

    private val BAND_MOTION = arrayOf(
        BandMotion.WAKE,
        BandMotion.STILL,
        BandMotion.SLEEP,
        BandMotion.UP,
    )

    fun decode(bytes: ByteArray): HistoryRecord {
        if (bytes.size <= LAYOUT_OFFSET) {
            return HistoryRecord.Rejected(RecordRejectReason.WRONG_LENGTH)
        }
        val layout = u8(bytes, LAYOUT_OFFSET)
        if (layout != LAYOUT) {
            return HistoryRecord.Unknown(layout, bytes)
        }
        if (bytes.size != RECORD_BYTES) {
            return HistoryRecord.Rejected(RecordRejectReason.WRONG_LENGTH)
        }
        when (val envelope = FrameCodec.decode(bytes)) {
            is DecodeResult.Rejected ->
                return HistoryRecord.Rejected(envelope.reason.toRecordReject())
            is DecodeResult.Ok -> Unit
        }
        return readLayout18(bytes)
    }

    private fun readLayout18(bytes: ByteArray): HistoryRecord.Layout18 {
        val recordIndex = readU32Le(bytes, 11)
        val unixSeconds = readU32Le(bytes, 15)
        val heartRateRaw = u8(bytes, 22)
        val rrCount = u8(bytes, 23)
        val rrWords = minOf(rrCount, RR_WORDS)
        val rrTicks = IntArray(rrWords) { index -> readU16Le(bytes, RR_OFFSET + 2 * index) }
        val heartRateBpm = heartRateRaw.takeIf { it in HR_MIN..HR_MAX }
        val activityRaw = u8(bytes, 63)
        val motionRaw = u8(bytes, 81)
        val restState = (motionRaw ushr 4) and 3
        val skinTempRaw = readU16Le(bytes, 73)
        val skinTempC = (skinTempRaw.toShort().toInt() / 100f).takeIf { it in 5f..45f }
        val dynamicBits = readU32Le(bytes, 41).toInt()
        val axisBits = intArrayOf(
            readU32Le(bytes, 45).toInt(),
            readU32Le(bytes, 49).toInt(),
            readU32Le(bytes, 53).toInt(),
        )
        val auxRaw = intArrayOf(readI16Le(bytes, 69), readI16Le(bytes, 71))
        val reading = Layout18Reading(
            recordIndex = recordIndex,
            unixSeconds = unixSeconds,
            heartRateBpm = heartRateBpm,
            rrMillis = rrTicks.filter { it > 0 }.map(::rrTicksToMillis),
            stepCounter = readU16Le(bytes, 57),
            activity = when (activityRaw) {
                0 -> ActivityKind.STILL
                1 -> ActivityKind.WALK
                2 -> ActivityKind.RUN
                else -> null
            },
            skinTempC = skinTempC,
            bandState = BAND_MOTION[restState],
        )
        val stored = Layout18Stored(
            recordIndex = recordIndex,
            unixSeconds = unixSeconds,
            heartRateRaw = heartRateRaw,
            rrCount = rrCount,
            rrTicks = rrTicks,
            rrBlockRaw = bytes.copyOfRange(RR_OFFSET, RR_OFFSET + RR_WORDS * 2),
            cardiacRaw = u8(bytes, 33),
            qualityRaw = u8(bytes, 36),
            alternateHeartRateRaw = u8(bytes, 37),
            packedWordRaw = readU16Le(bytes, 38),
            dynamicAccel = Float.fromBits(dynamicBits).takeIf { it in 0f..8f },
            dynamicAccelBits = dynamicBits,
            axisAccel = FloatArray(3) { index ->
                val value = Float.fromBits(axisBits[index])
                if (value.isFinite()) value else Float.NaN
            },
            axisAccelBits = axisBits,
            stepCounter = reading.stepCounter,
            cadenceRaw = readU16Le(bytes, 59),
            hardwareCounter = readU16Le(bytes, 61),
            activityClass = activityRaw.takeIf { it in 0..2 },
            activityClassRaw = activityRaw,
            counterOverrideRaw = u8(bytes, 64),
            auxTempC = FloatArray(2) { index ->
                val celsius = auxRaw[index] / 10f
                if (celsius in 0f..60f) celsius else Float.NaN
            },
            auxTempRaw = auxRaw,
            skinTempRaw = skinTempRaw,
            statusRaw = intArrayOf(
                readU16Le(bytes, 75),
                readU16Le(bytes, 77),
                readU16Le(bytes, 79),
            ),
            motionRaw = motionRaw,
            restState = restState,
            sleepAdjacentRaw = u8(bytes, 82),
            crc32 = readU32Le(bytes, CRC_OFFSET),
            raw = bytes.copyOf(),
        )
        return HistoryRecord.Layout18(
            decoderVersion = VERSION,
            reading = reading,
            stored = stored,
        )
    }

    private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF
}

/**
 * Milliseconds for one positive R-R tick.
 *
 * Spec §9: `(ticks * 1000 + 512) / 1024`. The tick is 1/1024 s. The same
 * rounding is the project rule for standard Heart Rate Measurement (§11).
 * A zero tick is not positive, so it is left out of the display list.
 */
internal fun rrTicksToMillis(ticks: Int): Int {
    require(ticks > 0) { "R-R milliseconds are defined for positive ticks" }
    return ((ticks.toLong() * 1000L + 512L) / 1024L).toInt()
}

sealed class HistoryRecord {
    /**
     * One accepted layout-18 record.
     *
     * [decoderVersion] is [HistoryRecordDecoder.VERSION]. [reading] is safe to
     * show. [stored] keeps the raw companions, including bytes 37 and 82.
     */
    class Layout18(
        val decoderVersion: Int,
        val reading: Layout18Reading,
        val stored: Layout18Stored,
    ) : HistoryRecord()

    /** A layout this decoder does not interpret. [bytes] is a private copy. */
    class Unknown(
        val layout: Int,
        bytes: ByteArray,
    ) : HistoryRecord() {
        val bytes: ByteArray = bytes.copyOf()
    }

    class Rejected(val reason: RecordRejectReason) : HistoryRecord()
}

enum class RecordRejectReason {
    WRONG_LENGTH,
    BAD_CRC,
    BAD_ENVELOPE,
}

/**
 * Display fields for layout 18.
 *
 * Heart rate outside 25..230 and skin temperature outside 5..45 °C are null.
 * Those windows are project rules 14 and 15. Unconfirmed quantities stay on
 * [Layout18Stored].
 */
data class Layout18Reading(
    val recordIndex: Long,
    val unixSeconds: Long,
    val heartRateBpm: Int?,
    val rrMillis: List<Int>,
    val stepCounter: Int,
    val activity: ActivityKind?,
    val skinTempC: Float?,
    val bandState: BandMotion,
)

/** Documented activity labels. Any other raw class is omitted, including `0xFF`. */
enum class ActivityKind {
    STILL,
    WALK,
    RUN,
}

/**
 * Band motion/rest from bits 4–5 of frame byte 81.
 *
 * 0 WAKE, 1 STILL, 2 SLEEP, 3 UP. This is not a light/deep/REM hypnogram.
 * The other bits of that byte have no vocabulary and stay in the raw byte.
 */
enum class BandMotion {
    WAKE,
    STILL,
    SLEEP,
    UP,
}

/**
 * Raw companions for one layout-18 row.
 *
 * Typed acceleration and auxiliary temperatures use the retained scales from
 * spec §9 and are null (or NaN, for the float arrays) outside the stated
 * windows. The unit and the sensor identity stay unconfirmed, so these fields
 * are for storage, not for a display model. [alternateHeartRateRaw] is byte
 * 37. [sleepAdjacentRaw] is byte 82. Neither is a display value.
 *
 * [skinTempRaw] is the unsigned 16-bit word. Display Celsius treats that word
 * as two's complement before dividing by 100, so the unconfirmed −50.00
 * pattern is outside 5..45 °C and the reading is null. [auxTempC] uses NaN
 * when the retained 0..60 °C window rejects a value. Layout 18 has no
 * sub-second fraction; the commit draft leaves that field null.
 */
data class Layout18Stored(
    val recordIndex: Long,
    val unixSeconds: Long,
    val heartRateRaw: Int,
    val rrCount: Int,
    val rrTicks: IntArray,
    val rrBlockRaw: ByteArray,
    val cardiacRaw: Int,
    val qualityRaw: Int,
    val alternateHeartRateRaw: Int,
    val packedWordRaw: Int,
    val dynamicAccel: Float?,
    val dynamicAccelBits: Int,
    val axisAccel: FloatArray,
    val axisAccelBits: IntArray,
    val stepCounter: Int,
    val cadenceRaw: Int,
    val hardwareCounter: Int,
    val activityClass: Int?,
    val activityClassRaw: Int,
    val counterOverrideRaw: Int,
    val auxTempC: FloatArray,
    val auxTempRaw: IntArray,
    val skinTempRaw: Int,
    val statusRaw: IntArray,
    val motionRaw: Int,
    val restState: Int,
    val sleepAdjacentRaw: Int,
    val crc32: Long,
    val raw: ByteArray,
)

private fun RejectReason.toRecordReject(): RecordRejectReason = when (this) {
    RejectReason.BAD_HEADER_CRC, RejectReason.BAD_BODY_CRC -> RecordRejectReason.BAD_CRC
    RejectReason.BAD_START, RejectReason.BAD_FORMAT -> RecordRejectReason.BAD_ENVELOPE
    else -> RecordRejectReason.WRONG_LENGTH
}
