package app.pulse.ble

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.pulse.wire.codec.BandMotion
import app.pulse.wire.codec.BatteryCommand
import app.pulse.wire.codec.ClockCommand
import app.pulse.wire.codec.CommandResult
import app.pulse.wire.codec.DataRangeCommand
import app.pulse.wire.codec.DecodeResult
import app.pulse.wire.codec.FrameCodec
import app.pulse.wire.codec.HeaderWord
import app.pulse.wire.codec.HelloBody
import app.pulse.wire.codec.HelloCommand
import app.pulse.wire.codec.HistoryAckCommand
import app.pulse.wire.codec.HistoryMetadata
import app.pulse.wire.codec.HistoryRecord
import app.pulse.wire.codec.HistoryRecordDecoder
import app.pulse.wire.codec.HistoryStartCommand
import app.pulse.wire.codec.PacketType
import app.pulse.wire.codec.toCommandResponse
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SimulatedStrapEngineTest {
    private val today = LocalDate.of(2026, 11, 8)

    @org.junit.Before
    fun resetLatch() {
        DisconnectLatch.tripped = false
    }

    @Test
    fun flagDefaultsOffAndASecondLaunchDoesNotChangeIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(SimulatedStrap.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse(SimulatedStrap.isEnabled(context))
        val intent = android.content.Intent().putExtra(SimulatedStrap.EXTRA_ENABLED, false)
        assertFalse(SimulatedStrap.applyIntent(context, intent))
        assertFalse(SimulatedStrap.isEnabled(context))
    }

    @Test
    fun intentEnablesTheFlagAndNamesAScenario() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(SimulatedStrap.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        val intent = android.content.Intent()
            .putExtra(SimulatedStrap.EXTRA_ENABLED, true)
            .putExtra(SimulatedStrap.EXTRA_SCENARIO, "low_battery")
        assertTrue(SimulatedStrap.applyIntent(context, intent))
        assertTrue(SimulatedStrap.isEnabled(context))
        assertEquals(StrapScenario.LOW_BATTERY, SimulatedStrap.scenario(context))
        assertFalse(SimulatedStrap.applyIntent(context, intent))
    }

    @Test
    fun layout18CarriesHeartRateRrStepsAndSleep() {
        val history = SyntheticHistory(today, ZoneOffset.UTC)
        assertEquals(4 * SyntheticHistory.DAY_MINUTES + 3 * SyntheticHistory.NIGHT_SECONDS, history.total)
        val day = decode(history.frame(0))
        assertEquals(BandMotion.STILL, day.reading.bandState)
        assertTrue(day.reading.heartRateBpm!! in 25..230)
        assertTrue(day.reading.rrMillis.isEmpty())
        assertNull(day.reading.skinTempC)
        val night = decode(history.frame(SyntheticHistory.DAY_MINUTES))
        assertEquals(BandMotion.SLEEP, night.reading.bandState)
        assertTrue(night.reading.heartRateBpm!! in 48..54)
        assertEquals(1, night.reading.rrMillis.size)
        assertTrue(night.reading.stepCounter >= 0)
        assertEquals(33.2f, night.reading.skinTempC!!, 0.01f)
        val next = decode(history.frame(SyntheticHistory.DAY_MINUTES + 1))
        assertTrue(night.reading.rrMillis.single() != next.reading.rrMillis.single())
        val again = SyntheticHistory(today, ZoneOffset.UTC)
        assertTrue(history.frame(0).contentEquals(again.frame(0)))
        assertTrue(history.frame(SyntheticHistory.DAY_MINUTES).contentEquals(again.frame(SyntheticHistory.DAY_MINUTES)))
    }

    @Test
    fun helloBatteryClockAndRangeUseTheRealBodies() {
        val engine = engine(StrapScenario.LOW_BATTERY)
        val hello = response(engine, HelloCommand.bytes(1))
        assertEquals(CommandResult.SUCCESS, hello.result)
        assertEquals(SimulatedStrapEngine.FIRMWARE, HelloBody.parse(hello.body)?.versionText)
        val battery = response(engine, command(BatteryCommand.NUMBER, 2, ByteArray(0)))
        assertEquals(8L, BatteryCommand.percent(battery.body))
        val clock = response(engine, command(ClockCommand.GET, 3, ClockCommand.getBody()))
        assertEquals(1_700_000_000L, ClockCommand.parse(clock.body)?.unixSeconds)
        val range = engine.onCommand(command(DataRangeCommand.NUMBER, 4, ByteArray(0)))
        assertEquals(2, range.size)
        val pending = (FrameCodec.decode(range[0]) as DecodeResult.Ok).frame.toCommandResponse()!!
        val done = (FrameCodec.decode(range[1]) as DecodeResult.Ok).frame.toCommandResponse()!!
        assertEquals(CommandResult.PENDING, pending.result)
        assertEquals(CommandResult.SUCCESS, done.result)
        assertEquals(DataRangeCommand.BODY_BYTES, DataRangeCommand.snapshot(done.body)!!.size)
    }

    @Test
    fun chunksEndWithASendableTokenAndCommand23Succeeds() {
        val engine = engine(StrapScenario.NORMAL)
        engine.onCommand(command(HistoryStartCommand.NUMBER, 5, HistoryStartCommand.EXPLICIT_ZERO))
        val frames = ArrayList<ByteArray>()
        var end: ByteArray? = null
        repeat(20) {
            if (end != null) return@repeat
            when (val pull = engine.pull(200)) {
                is StreamPull.Frames -> frames += pull.frames
                is StreamPull.Disconnect -> error("normal does not drop")
                StreamPull.Done -> error("ended before a token")
            }
        }
        val token = frames.firstNotNullOf { frame ->
            val view = metadata(frame)
            if (view?.kind == HistoryMetadata.END) view.token else null
        }
        assertTrue(HistoryMetadata.echoable(token))
        assertTrue(HistoryAckCommand.sendable(token))
        val ack = command(HistoryAckCommand.NUMBER, 6, HistoryAckCommand.body(token))
        val reply = response(engine, ack)
        assertEquals(HistoryAckCommand.NUMBER, reply.command)
        assertEquals(CommandResult.SUCCESS, reply.result)
        assertEquals(1, engine.acknowledgements)
    }

    @Test
    fun emptyStrapCompletesWithoutRecords() {
        val engine = engine(StrapScenario.EMPTY)
        engine.onCommand(command(HistoryStartCommand.NUMBER, 1, HistoryStartCommand.EXPLICIT_ZERO))
        val pull = engine.pull(10) as StreamPull.Frames
        val kinds = pull.frames.map { metadata(it)!!.kind }
        assertEquals(listOf(HistoryMetadata.START, HistoryMetadata.COMPLETE), kinds)
        assertEquals(StreamPull.Done, engine.pull(10))
    }

    @Test
    fun disconnectDropsAfterOneSealedChunk() {
        val engine = engine(StrapScenario.DISCONNECT)
        engine.onCommand(command(HistoryStartCommand.NUMBER, 1, HistoryStartCommand.EXPLICIT_ZERO))
        var ends = 0
        var records = 0
        var dropped = false
        repeat(30) {
            if (dropped) return@repeat
            when (val pull = engine.pull(200)) {
                is StreamPull.Frames -> {
                    pull.frames.forEach { frame ->
                        val view = metadata(frame)
                        if (view?.kind == HistoryMetadata.END) ends += 1
                        if (view == null && isRecord(frame)) records += 1
                    }
                }
                is StreamPull.Disconnect -> {
                    dropped = true
                    pull.frames.forEach { frame ->
                        if (metadata(frame) == null && isRecord(frame)) records += 1
                    }
                }
                StreamPull.Done -> error("disconnect did not drop")
            }
        }
        assertTrue(dropped)
        assertEquals(1, ends)
        assertTrue(records > SyntheticHistory.CHUNK_RECORDS)
        assertTrue(DisconnectLatch.tripped)
    }

    @Test
    fun liveHeartRateMovesSlowly() {
        val beats = (0 until 120).map { SimulatedStrapEngine.heartRateBpm(it) }
        assertTrue(beats.all { it in 54..90 })
        val steps = beats.zipWithNext { a, b -> kotlin.math.abs(a - b) }
        assertTrue(steps.max() <= 6)
        assertTrue(beats.max() - beats.min() >= 6)
        val first = SimulatedStrapEngine.heartRateNotification(0)
        assertEquals(0, first[0].toInt())
        assertEquals(beats.first(), first[1].toInt())
    }

    private fun engine(scenario: StrapScenario) = SimulatedStrapEngine(
        scenario = scenario,
        today = today,
        zone = ZoneOffset.UTC,
        clockSeconds = { 1_700_000_000L },
    )

    private fun command(command: Int, sequence: Int, body: ByteArray): ByteArray =
        FrameCodec.encode(
            headerWord = HeaderWord.COMMON_REQUEST,
            packetType = PacketType.COMMAND,
            sequence = sequence,
            command = command,
            body = body,
        )

    private fun response(engine: SimulatedStrapEngine, request: ByteArray) =
        (FrameCodec.decode(engine.onCommand(request).single()) as DecodeResult.Ok).frame.toCommandResponse()!!

    private fun decode(frame: ByteArray): HistoryRecord.Layout18 =
        HistoryRecordDecoder.decode(frame) as HistoryRecord.Layout18

    private fun metadata(frame: ByteArray) =
        (FrameCodec.decode(frame) as? DecodeResult.Ok)?.frame?.let(HistoryMetadata::read)

    private fun isRecord(frame: ByteArray): Boolean {
        val decoded = FrameCodec.decode(frame)
        return decoded is DecodeResult.Ok && decoded.frame.packetType == PacketType.HISTORY_RECORD
    }
}
