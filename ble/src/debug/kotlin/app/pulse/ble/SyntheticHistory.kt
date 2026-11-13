package app.pulse.ble

import app.pulse.wire.codec.FrameCodec
import app.pulse.wire.codec.HeaderWord
import app.pulse.wire.codec.HistoryMetadata
import app.pulse.wire.codec.HistoryRecordDecoder
import app.pulse.wire.codec.PacketType
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin

/**
 * Layout-18 history generated from [SEED].
 *
 * Fifteen local days of one-minute daytime samples ending on [today], and
 * fourteen nights of one sample per second. The newest night starts the
 * evening before [today] and wakes on [today], so the home window for today
 * contains sleep. Sleep duration on the home screen is the number of sleep
 * samples, so a night is stored at 1 Hz. Chunk tokens include [epochDay], so
 * a later calendar day is stored instead of matching an older series.
 * Nothing here is copied from a person's strap.
 */
internal class SyntheticHistory(
    private val today: LocalDate,
    private val zone: ZoneId,
    private val seed: Int = SEED,
) {
    val epochDay: Int = today.toEpochDay().toInt()
    private val daySteps: Array<IntArray> = Array(DAY_COUNT) { ordinal ->
        val values = IntArray(DAY_MINUTES)
        var sum = 0
        for (minute in 0 until DAY_MINUTES) {
            val hour = DAY_START_HOUR + minute / 60
            if (hour in 8..18) sum += 8 + (mix(seed, ordinal * 10_000 + minute) % 8)
            values[minute] = sum
        }
        values
    }

    private val segments: List<Segment> = buildList {
        var cursor = 0
        fun add(kind: Kind, day: LocalDate, count: Int, stepDay: Int) {
            add(Segment(cursor, count, kind, day, stepDay))
            cursor += count
        }
        for (index in 0 until DAY_COUNT) {
            val day = today.minusDays((DAY_COUNT - 1 - index).toLong())
            add(Kind.DAY, day, DAY_MINUTES, index)
            if (index < NIGHT_COUNT) add(Kind.NIGHT, day.plusDays(1), NIGHT_SECONDS, index)
        }
    }

    val total: Int = segments.sumOf { it.count }

    fun sample(index: Int): SyntheticSample {
        val segment = segments.first { index >= it.start && index < it.start + it.count }
        val offset = index - segment.start
        return when (segment.kind) {
            Kind.DAY -> daySample(index, segment, offset)
            Kind.NIGHT -> nightSample(index, segment, offset)
        }
    }

    fun frame(index: Int): ByteArray {
        val sample = sample(index)
        val body = ByteArray(RECORD_BODY)
        writeU32(body, 0, index.toLong())
        writeU32(body, 4, sample.unixSeconds)
        body[11] = sample.heartRate.toByte()
        if (sample.rrTick != null) {
            body[12] = 1
            writeU16(body, 13, sample.rrTick)
        }
        writeU16(body, 46, sample.steps)
        body[52] = sample.activity.toByte()
        body[70] = sample.motion
        writeU16(body, 62, sample.skinTempRaw)
        return FrameCodec.encode(
            headerWord = HeaderWord.COMMON_RESPONSE,
            packetType = PacketType.HISTORY_RECORD,
            sequence = HistoryRecordDecoder.LAYOUT,
            command = 0,
            body = body,
        )
    }

    private fun daySample(index: Int, segment: Segment, minute: Int): SyntheticSample {
        val start = segment.day.atTime(DAY_START_HOUR, 0).atZone(zone).toEpochSecond()
        val hour = DAY_START_HOUR + minute / 60
        val raised = hour in 12..13
        val heart = (if (raised) 128 else 72) + (mix(seed, index) % 12)
        return SyntheticSample(
            unixSeconds = start + minute * 60L,
            heartRate = heart,
            rrTick = null,
            steps = daySteps[segment.stepDay][minute],
            activity = if (hour in 8..18) 1 else 0,
            motion = 0x10.toByte(),
        )
    }

    private fun nightSample(index: Int, segment: Segment, second: Int): SyntheticSample {
        val extraMinutes = if (segment.stepDay == NIGHT_COUNT - 1) 0 else 15 + (segment.stepDay % 5) * 18
        val start = segment.day.minusDays(1).atTime(22, 0).plusMinutes(extraMinutes.toLong()).atZone(zone).toEpochSecond()
        val phase = second % (90 * 60)
        val heart = when {
            phase < 20 * 60 -> 46 + (mix(seed, index) % 3)
            phase < 50 * 60 -> 52 + (mix(seed, index) % 4)
            phase < 70 * 60 -> if (second % 2 == 0) 56 else 60
            else -> 50 + (mix(seed, index) % 4)
        }
        val wake = second in 40 * 60 until 40 * 60 + 45 ||
            second in 3 * 3600 until 3 * 3600 + 40 ||
            second in 5 * 3600 until 5 * 3600 + 50
        val wave = sin(2.0 * PI * (second % 4) / 4.0)
        val noise = (mix(seed, index xor 0x5A5A) % 11) - 5
        val rr = (1024.0 + wave * 50.0 + noise).toInt()
        return SyntheticSample(
            unixSeconds = start + second,
            heartRate = if (wake) 74 + (mix(seed, index) % 6) else heart,
            rrTick = rr,
            steps = daySteps[segment.stepDay][DAY_MINUTES - 1],
            activity = 0,
            motion = if (wake) 0x00.toByte() else 0x20.toByte(),
            skinTempRaw = 3320 + segment.stepDay * 15,
        )
    }

    private class Segment(
        val start: Int,
        val count: Int,
        val kind: Kind,
        val day: LocalDate,
        val stepDay: Int,
    )

    private enum class Kind { DAY, NIGHT }

    companion object {
        const val SEED: Int = 0x50554C53
        const val NIGHT_SECONDS: Int = 7 * 3600
        const val DAY_START_HOUR: Int = 7
        const val DAY_MINUTES: Int = 15 * 60
        const val DAY_COUNT: Int = 15
        const val NIGHT_COUNT: Int = 14
        const val RECORD_BODY: Int = 109
        const val CHUNK_RECORDS: Int = 1000

        fun metadata(kind: Int, token: ByteArray? = null): ByteArray {
            val body = if (token == null) {
                byteArrayOf(0)
            } else {
                ByteArray(18).also { token.copyInto(it, destinationOffset = 10) }
            }
            return FrameCodec.encode(
                headerWord = HeaderWord.COMMON_RESPONSE,
                packetType = PacketType.HISTORY_METADATA,
                sequence = 0,
                command = kind,
                body = body,
            )
        }

        fun chunkToken(chunk: Int, epochDay: Int): ByteArray = byteArrayOf(
            (epochDay and 0xFF).toByte(),
            ((epochDay ushr 8) and 0xFF).toByte(),
            ((epochDay ushr 16) and 0xFF).toByte(),
            0x40,
            (chunk and 0xFF).toByte(),
            ((chunk ushr 8) and 0xFF).toByte(),
            0x01,
            0x7A,
        )

        fun mix(seed: Int, value: Int): Int {
            var x = value.toLong() xor seed.toLong()
            x = x xor (x ushr 33)
            x *= 0xff51afd7ed558ccdUL.toLong()
            x = x xor (x ushr 33)
            x *= 0xc4ceb9fe1a85ec53UL.toLong()
            x = x xor (x ushr 33)
            return (x and 0x7fffffff).toInt()
        }

        private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = value.toByte()
            bytes[offset + 1] = (value ushr 8).toByte()
        }

        private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
            bytes[offset] = value.toByte()
            bytes[offset + 1] = (value ushr 8).toByte()
            bytes[offset + 2] = (value ushr 16).toByte()
            bytes[offset + 3] = (value ushr 24).toByte()
        }
    }
}

internal class SyntheticSample(
    val unixSeconds: Long,
    val heartRate: Int,
    val rrTick: Int?,
    val steps: Int,
    val activity: Int,
    val motion: Byte,
    val skinTempRaw: Int = 0,
)

internal fun endMetadata(chunk: Int, epochDay: Int): ByteArray =
    SyntheticHistory.metadata(HistoryMetadata.END, SyntheticHistory.chunkToken(chunk, epochDay))
