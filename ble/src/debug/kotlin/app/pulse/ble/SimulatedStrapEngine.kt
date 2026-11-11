package app.pulse.ble

import app.pulse.wire.codec.BatteryCommand
import app.pulse.wire.codec.ClockCommand
import app.pulse.wire.codec.CommandResult
import app.pulse.wire.codec.DataRangeCommand
import app.pulse.wire.codec.DecodeResult
import app.pulse.wire.codec.FrameCodec
import app.pulse.wire.codec.HelloBody
import app.pulse.wire.codec.HelloCommand
import app.pulse.wire.codec.HistoryAckCommand
import app.pulse.wire.codec.HistoryMetadata
import app.pulse.wire.codec.HistoryStartCommand
import app.pulse.wire.codec.PacketType
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Scripted strap for one debug session.
 *
 * Replies use the same frame codec as the phone. History is pushed in chunks
 * with end tokens and does not wait for command 23. A command 23 write still
 * gets result 1. The series is [SyntheticHistory], not a stored recording.
 */
internal class SimulatedStrapEngine(
    val scenario: StrapScenario,
    today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    zone: ZoneId = ZoneId.systemDefault(),
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    val history: SyntheticHistory = SyntheticHistory(today, zone)
    val batteryPercent: Int = when (scenario) {
        StrapScenario.LOW_BATTERY -> 8
        StrapScenario.EMPTY -> 91
        StrapScenario.NORMAL, StrapScenario.DISCONNECT -> 76
    }

    var acknowledgements: Int = 0
        private set

    private val interrupt = scenario == StrapScenario.DISCONNECT && !DisconnectLatch.tripped
    private var responseSequence = 1
    private var armed = false
    private var cursor = Cursor.IDLE
    private var index = 0
    private var inChunk = 0
    private var chunk = 0

    fun onCommand(frame: ByteArray): List<ByteArray> {
        val decoded = FrameCodec.decode(frame)
        if (decoded !is DecodeResult.Ok) return emptyList()
        val wire = decoded.frame
        if (wire.packetType != PacketType.COMMAND) return emptyList()
        val command = wire.command
        val origin = wire.sequence
        return when (command) {
            HelloCommand.NUMBER -> listOf(reply(origin, command, CommandResult.SUCCESS, helloBody()))
            BatteryCommand.NUMBER -> listOf(reply(origin, command, CommandResult.SUCCESS, batteryBody()))
            ClockCommand.GET, ClockCommand.SET ->
                listOf(reply(origin, command, CommandResult.SUCCESS, clockBody()))
            DataRangeCommand.NUMBER -> listOf(
                reply(origin, command, CommandResult.PENDING, ByteArray(0)),
                reply(origin, command, CommandResult.SUCCESS, rangeBody()),
            )
            HistoryStartCommand.NUMBER -> {
                if (cursor == Cursor.IDLE) {
                    cursor = Cursor.READY
                    armed = true
                }
                listOf(reply(origin, command, CommandResult.SUCCESS, byteArrayOf(0)))
            }
            HistoryAckCommand.NUMBER -> {
                acknowledgements += 1
                listOf(reply(origin, command, CommandResult.SUCCESS, ByteArray(0)))
            }
            else -> listOf(reply(origin, command, CommandResult.UNSUPPORTED, ByteArray(0)))
        }
    }

    fun consumeArm(): Boolean {
        val start = armed
        armed = false
        return start
    }

    fun pull(maxFrames: Int): StreamPull {
        if (cursor == Cursor.IDLE || cursor == Cursor.DONE) return StreamPull.Done
        val frames = ArrayList<ByteArray>(maxFrames)
        var disconnect = false
        var pause = false
        while (frames.size < maxFrames && cursor != Cursor.DONE && !disconnect && !pause) {
            when (cursor) {
                Cursor.READY -> {
                    frames += SyntheticHistory.metadata(HistoryMetadata.START)
                    if (scenario == StrapScenario.EMPTY || index >= history.total) {
                        cursor = Cursor.FINISH
                    } else {
                        inChunk = 0
                        cursor = Cursor.RECORDS
                    }
                }
                Cursor.RECORDS -> {
                    val drop = interrupt && chunk == 1 && inChunk >= PARTIAL_RECORDS
                    if (drop) {
                        disconnect = true
                    } else if (inChunk >= SyntheticHistory.CHUNK_RECORDS || index >= history.total) {
                        cursor = Cursor.SEAL
                    } else {
                        frames += history.frame(index)
                        index += 1
                        inChunk += 1
                    }
                }
                Cursor.SEAL -> {
                    frames += endMetadata(chunk)
                    chunk += 1
                    cursor = if (index >= history.total) Cursor.FINISH else Cursor.READY
                    pause = true
                }
                Cursor.FINISH -> {
                    frames += SyntheticHistory.metadata(HistoryMetadata.COMPLETE)
                    cursor = Cursor.DONE
                }
                Cursor.IDLE, Cursor.DONE -> Unit
            }
        }
        if (disconnect) {
            DisconnectLatch.tripped = true
            cursor = Cursor.DONE
            return StreamPull.Disconnect(frames)
        }
        if (frames.isEmpty()) return StreamPull.Done
        return StreamPull.Frames(frames)
    }

    private fun reply(origin: Int, command: Int, result: Int, body: ByteArray): ByteArray {
        val sequence = responseSequence
        responseSequence = (responseSequence + 1) and 0xFF
        if (responseSequence == 0) responseSequence = 1
        return FrameCodec.encodeCommandResponse(
            sequence = sequence,
            command = command,
            originSequence = origin,
            result = result,
            body = body,
        )
    }

    private fun helloBody(): ByteArray {
        val body = ByteArray(HelloBody.FINAL_LENGTH)
        body[0] = HelloCommand.REVISION.toByte()
        body[HelloBody.VERSION_OFFSET] = 50
        body[HelloBody.VERSION_OFFSET + 1] = 43
        body[HelloBody.VERSION_OFFSET + 2] = 1
        return body
    }

    private fun batteryBody(): ByteArray {
        val body = ByteArray(4)
        val percent = batteryPercent.toLong()
        body[0] = percent.toByte()
        body[1] = (percent ushr 8).toByte()
        body[2] = (percent ushr 16).toByte()
        body[3] = (percent ushr 24).toByte()
        return body
    }

    private fun clockBody(): ByteArray = ClockCommand.setBody(clockSeconds().coerceIn(1L, 0xFFFF_FFFFL), 0)

    private fun rangeBody(): ByteArray {
        val body = ByteArray(DataRangeCommand.BODY_BYTES)
        val seed = SyntheticHistory.SEED
        body[0] = seed.toByte()
        body[1] = (seed ushr 8).toByte()
        body[2] = (seed ushr 16).toByte()
        body[3] = (seed ushr 24).toByte()
        return body
    }

    private enum class Cursor { IDLE, READY, RECORDS, SEAL, FINISH, DONE }

    companion object {
        const val FIRMWARE: String = "50.43.1.0"
        const val PARTIAL_RECORDS: Int = 40

        /**
         * A slow resting trace plus a little noise.
         *
         * The earlier `(tick * 17) % 21` series was a sawtooth and drew as a
         * zigzag. Consecutive beats stay within a few bpm.
         */
        fun heartRateBpm(tick: Int): Int {
            val slow = sin(tick / 16.0) * 5.0 + sin(tick / 41.0) * 2.0
            val noise = (tick * 13) % 5 - 2
            return (66.0 + slow + noise).roundToInt().coerceIn(54, 90)
        }

        fun heartRateNotification(tick: Int): ByteArray =
            byteArrayOf(0x00, heartRateBpm(tick).toByte())
    }
}

internal sealed class StreamPull {
    class Frames(val frames: List<ByteArray>) : StreamPull()

    class Disconnect(val frames: List<ByteArray>) : StreamPull()

    data object Done : StreamPull()
}

/**
 * One disconnect per process. The link scans again after a drop, and the
 * next session should finish instead of dropping in a loop.
 */
internal object DisconnectLatch {
    var tripped: Boolean = false
}
