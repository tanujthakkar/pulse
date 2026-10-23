package app.pulse.sync

import app.pulse.testing.fakestrap.FakeStrap
import app.pulse.testing.fakestrap.ScriptChunk
import app.pulse.testing.fakestrap.StrapScript
import app.pulse.testing.fakestrap.StreamKind
import app.pulse.testing.fakestrap.eventFrame
import app.pulse.testing.fakestrap.sampleRecord
import app.pulse.testing.fakestrap.sampleToken
import app.pulse.testing.fakestrap.unknownRecord
import app.pulse.wire.codec.ClockCommand
import app.pulse.wire.codec.CommandResult
import app.pulse.wire.codec.DataRangeCommand
import app.pulse.wire.codec.DecodeResult
import app.pulse.wire.codec.DeniedCommands
import app.pulse.wire.codec.FrameCodec
import app.pulse.wire.codec.HistoryAckCommand
import app.pulse.wire.codec.HistoryAckGate
import app.pulse.wire.codec.HistoryRecordDecoder
import app.pulse.wire.codec.HistoryStartCommand
import app.pulse.wire.codec.PacketType
import java.util.ArrayDeque
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistorySyncEngineTest {
    @Test
    fun happyPathSetsTheClockThenAcksAfterCommit() {
        val store = MemorySyncStore()
        val token = sampleToken()
        val record = sampleRecord()
        val script = script(
            strapUnixSeconds = PHONE + 10,
            chunks = listOf(ScriptChunk(token, listOf(record))),
        )
        val run = run(store, script)
        assertEquals(SessionOutcome.COMPLETE, run.engine.outcome)
        assertEquals(listOf(147, 146, 147, 34, 22), sentCommands(run.engine))
        assertArrayEquals(byteArrayOf(0), sentBody(run.engine, 22))
        assertArrayEquals(script.range, run.engine.rangeSnapshot)
        assertArrayEquals(script.range, store.sessions.single().rangeSnapshot)
        assertEquals(DataRangeCommand.BODY_BYTES, store.sessions.single().rangeSnapshot.size)
        assertEquals(PHONE, store.sessions.single().startedUnixSeconds)
        assertEquals(PHONE_TICKS, store.sessions.single().startedSubsecondTicks)
        assertEquals(PHONE, store.sessions.single().endedUnixSeconds)
        val chunk = store.chunks.single()
        assertArrayEquals(token, chunk.token)
        assertEquals(1, chunk.samples.size)
        assertEquals(72, chunk.samples.single().heartRateBpm)
        assertEquals(41L, chunk.samples.single().recordIndex)
        assertEquals(HistoryRecordDecoder.VERSION, chunk.samples.single().decoderVersion)
        assertEquals(1, commit(run.engine).samples)
        assertEquals(1, commit(run.engine).frames)
        assertEquals(0, commit(run.engine).archived)
        assertFalse(commit(run.engine).duplicate)
        assertAckToken(run, token)
        assertEquals(PHONE, chunk.ackedAt)
        assertAcksFollowCommits(run.engine.events)
        assertNoDeniedCommands(run.written)
        assertTrue(run.written.map { commandOf(it) }.contains(HistoryAckCommand.NUMBER))
        assertFalse(HistoryAckGate.maySend())
        assertFalse(HistoryAckGate.OFFSETS_CONFIRMED)
    }

    @Test
    fun driftOfTwoSecondsDoesNotSetTheClock() {
        val store = MemorySyncStore()
        val run = run(store, script(strapUnixSeconds = PHONE + 2, chunks = listOf(emptyChunk())))
        assertEquals(listOf(147, 34, 22), sentCommands(run.engine))
        assertEquals(SessionOutcome.COMPLETE, run.engine.outcome)
        assertEquals(PHONE + 2, store.sessions.single().startedUnixSeconds)
        assertAcksFollowCommits(run.engine.events)
        assertNoDeniedCommands(run.written)
    }

    @Test
    fun zeroClockAbortsBeforeHistory() {
        val store = MemorySyncStore()
        val run = run(store, script(strapUnixSeconds = 0))
        assertEquals(SessionOutcome.ABORTED, run.engine.outcome)
        assertEquals(listOf(147), sentCommands(run.engine))
        assertTrue(store.sessions.isEmpty())
        assertTrue(store.chunks.isEmpty())
        assertNoAcks(run)
    }

    @Test
    fun emptyEndCommitsNothingThenAcks() {
        val store = MemorySyncStore()
        val token = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        val run = run(store, script(chunks = listOf(ScriptChunk(token, emptyList()))))
        val committed = commit(run.engine)
        assertEquals(0, committed.frames)
        assertEquals(0, committed.samples)
        assertEquals(0, committed.archived)
        assertFalse(committed.duplicate)
        assertAckToken(run, token)
        assertEquals(SessionOutcome.COMPLETE, run.engine.outcome)
        assertAcksFollowCommits(run.engine.events)
        assertNoDeniedCommands(run.written)
    }

    @Test
    fun duplicateEndAcksBothTimesAfterEachCommit() {
        val store = MemorySyncStore()
        val token = sampleToken()
        val run = run(
            store,
            script(chunks = listOf(ScriptChunk(token, listOf(sampleRecord()), extraEnds = 1))),
        )
        val commits = run.engine.events.filterIsInstance<SyncTrace.Commit>()
        assertEquals(2, commits.size)
        assertFalse(commits[0].duplicate)
        assertTrue(commits[1].duplicate)
        assertEquals(1, store.chunks.size)
        val acks = run.engine.events.filterIsInstance<SyncTrace.Ack>()
        assertEquals(2, acks.size)
        assertArrayEquals(token, acks[0].token)
        assertArrayEquals(token, acks[1].token)
        assertEquals(SessionOutcome.COMPLETE, run.engine.outcome)
        assertAcksFollowCommits(run.engine.events)
        assertNoDeniedCommands(run.written)
    }

    @Test
    fun disconnectBeforeEndDropsTheChunkAndDoesNotAck() {
        val store = MemorySyncStore()
        val run = run(
            store,
            script(stream = StreamKind.DISCONNECT, chunks = listOf(ScriptChunk(sampleToken(), listOf(sampleRecord())))),
        )
        assertEquals(SessionOutcome.DISCONNECTED, run.engine.outcome)
        assertTrue(store.chunks.isEmpty())
        assertTrue(run.engine.events.any { it is SyncTrace.DroppedChunk })
        assertEquals(PHONE, store.sessions.single().endedUnixSeconds)
        assertNoAcks(run)
        assertNoDeniedCommands(run.written)
    }

    @Test
    fun storeThrowAbortsWithoutAck() {
        val store = MemorySyncStore().also { it.failCommit = true }
        val run = run(store, script())
        assertEquals(SessionOutcome.ABORTED, run.engine.outcome)
        assertTrue(store.chunks.isEmpty())
        assertTrue(run.engine.events.any { it is SyncTrace.Commit }.not())
        assertEquals(PHONE, store.sessions.single().endedUnixSeconds)
        assertNoAcks(run)
        assertNoDeniedCommands(run.written)
    }

    @Test
    fun replayAfterReconnectIgnoresDuplicatesAndStillAcks() {
        val store = MemorySyncStore()
        val token = sampleToken()
        val record = sampleRecord()
        val first = run(store, script(chunks = listOf(ScriptChunk(token, listOf(record)))))
        val second = run(store, script(chunks = listOf(ScriptChunk(token, listOf(record)))))
        assertEquals(SessionOutcome.COMPLETE, second.engine.outcome)
        assertTrue(commit(second.engine).duplicate)
        assertEquals(0, commit(second.engine).samples)
        assertEquals(0, commit(second.engine).frames)
        assertAckToken(second, token)
        assertEquals(1, store.chunks.size)
        assertEquals(1, store.chunks.single().samples.size)
        assertAcksFollowCommits(first.engine.events)
        assertAcksFollowCommits(second.engine.events)
        assertNoDeniedCommands(second.written)
    }

    @Test
    fun sameRecordUnderANewTokenIsNotInsertedTwiceAndIsStillAcked() {
        val store = MemorySyncStore()
        val record = sampleRecord()
        val firstToken = byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1)
        val secondToken = byteArrayOf(2, 2, 2, 2, 2, 2, 2, 2)
        val run = run(
            store,
            script(
                chunks = listOf(
                    ScriptChunk(firstToken, listOf(record)),
                    ScriptChunk(secondToken, listOf(record)),
                ),
            ),
        )
        val commits = run.engine.events.filterIsInstance<SyncTrace.Commit>()
        assertEquals(2, commits.size)
        assertEquals(1, commits[0].samples)
        assertEquals(0, commits[1].samples)
        assertEquals(0, commits[1].frames)
        assertFalse(commits[1].duplicate)
        assertEquals(listOf(firstToken.toList(), secondToken.toList()), store.chunks.map { it.token.toList() })
        assertEquals(1, store.chunks[0].samples.size)
        assertEquals(1, store.chunks[1].samples.size)
        assertAcksFollowCommits(run.engine.events)
        assertEquals(2, run.strap.ackTokens.size)
        assertNoDeniedCommands(run.written)
    }

    @Test
    fun completeWithoutEndClosesAndAcksNothing() {
        val store = MemorySyncStore()
        val run = run(
            store,
            script(
                stream = StreamKind.COMPLETE_WITHOUT_END,
                chunks = listOf(ScriptChunk(sampleToken(), listOf(sampleRecord()))),
            ),
        )
        assertEquals(SessionOutcome.COMPLETE, run.engine.outcome)
        assertTrue(store.chunks.isEmpty())
        assertTrue(run.engine.events.any { it is SyncTrace.DroppedChunk })
        assertEquals(PHONE, store.sessions.single().endedUnixSeconds)
        assertNoAcks(run)
        assertNoDeniedCommands(run.written)
    }

    @Test
    fun watchdogExpiresWithoutAckingAnOpenChunk() {
        val clock = ManualMillis()
        val store = MemorySyncStore()
        val run = run(
            store,
            script(stream = StreamKind.SILENT),
            clock = clock,
        )
        assertTrue(run.engine.isActive)
        clock.value = HistoryLimits.WATCHDOG_MS - 1
        assertFalse(run.engine.onWatchdog())
        assertTrue(run.engine.isActive)
        clock.value = HistoryLimits.WATCHDOG_MS
        assertTrue(run.engine.onWatchdog())
        assertEquals(SessionOutcome.WATCHDOG, run.engine.outcome)
        assertTrue(store.chunks.isEmpty())
        assertEquals(PHONE, store.sessions.single().endedUnixSeconds)
        assertNoAcks(run)
        assertNoDeniedCommands(run.written)
    }

    @Test
    fun historyTrafficRestartsTheWatchdog() {
        val clock = ManualMillis()
        val store = MemorySyncStore()
        val run = run(store, script(stream = StreamKind.SILENT), clock = clock)
        clock.value = 50_000
        assertFalse(run.engine.onWatchdog())
        run.engine.onNotification("notify", eventFrame())
        clock.value = 50_000 + HistoryLimits.WATCHDOG_MS - 1
        assertFalse(run.engine.onWatchdog())
        clock.value = 50_000 + HistoryLimits.WATCHDOG_MS
        assertTrue(run.engine.onWatchdog())
        assertEquals(SessionOutcome.WATCHDOG, run.engine.outcome)
        assertNoAcks(run)
    }

    @Test
    fun twoThousandRecordsWithoutEndAbortAndDoNotAck() {
        val store = MemorySyncStore()
        val run = run(store, script(stream = StreamKind.FLOOD, floodCount = HistoryLimits.MAX_RECORDS_WITHOUT_END))
        assertEquals(HistoryLimits.MAX_RECORDS_WITHOUT_END, 2_000)
        assertEquals(SessionOutcome.BOUNDED, run.engine.outcome)
        assertEquals(SyncPhase.DONE, run.engine.snapshot().phase)
        assertEquals(HistoryLimits.MAX_RECORDS_WITHOUT_END, run.engine.snapshot().records)
        assertEquals(0, run.engine.snapshot().chunks)
        assertEquals(SessionOutcome.BOUNDED, run.engine.snapshot().outcome)
        assertTrue(store.chunks.isEmpty())
        assertTrue(run.engine.events.any { it is SyncTrace.DroppedChunk })
        assertNoAcks(run)
        assertNoDeniedCommands(run.written)
    }

    @Test
    fun unknownLayoutIsArchivedInTheSameCommitThenAcked() {
        val store = MemorySyncStore()
        val token = byteArrayOf(3, 1, 4, 1, 5, 9, 2, 6)
        val record = unknownRecord(layout = 99, mark = 44)
        val run = run(store, script(chunks = listOf(ScriptChunk(token, listOf(record)))))
        val committed = commit(run.engine)
        assertEquals(0, committed.frames)
        assertEquals(0, committed.samples)
        assertEquals(1, committed.archived)
        assertArrayEquals(record, store.chunks.single().archived.single().bytes)
        assertEquals(99, store.chunks.single().archived.single().layout)
        assertAckToken(run, token)
        assertEquals(SessionOutcome.COMPLETE, run.engine.outcome)
        assertAcksFollowCommits(run.engine.events)
        assertNoDeniedCommands(run.written)
    }

    @Test
    fun endWithoutATokenIsNotAcknowledged() {
        val store = MemorySyncStore()
        val run = run(
            store,
            script(chunks = listOf(ScriptChunk(sampleToken(), listOf(sampleRecord()), omitToken = true))),
        )
        assertEquals(SessionOutcome.ABORTED, run.engine.outcome)
        assertTrue(store.chunks.isEmpty())
        assertNoAcks(run)
    }

    @Test
    fun historyStartRetriesOnceWithAnEmptyBody() {
        val store = MemorySyncStore()
        val run = run(
            store,
            script(
                startResults = listOf(CommandResult.UNSUPPORTED, CommandResult.SUCCESS),
                chunks = listOf(emptyChunk()),
            ),
        )
        val starts = run.engine.events.filterIsInstance<SyncTrace.Sent>().filter { it.command == 22 }
        assertEquals(2, starts.size)
        assertArrayEquals(byteArrayOf(0), starts[0].body)
        assertEquals(0, starts[1].body.size)
        assertEquals(SessionOutcome.COMPLETE, run.engine.outcome)
        assertAcksFollowCommits(run.engine.events)
    }

    @Test
    fun historyStartFailureAndFailStateAbortWithoutAck() {
        val failed = run(
            MemorySyncStore(),
            script(startResults = listOf(CommandResult.FAILURE, CommandResult.FAILURE)),
        )
        assertEquals(SessionOutcome.ABORTED, failed.engine.outcome)
        assertTrue(failed.store.sessions.single().endedUnixSeconds != null)
        assertNoAcks(failed)

        val state = run(MemorySyncStore(), script(startState = 6))
        assertEquals(SessionOutcome.ABORTED, state.engine.outcome)
        assertTrue(state.store.chunks.isEmpty())
        assertNoAcks(state)
        assertNoDeniedCommands(state.written)
    }

    @Test
    fun shortDataRangeAbortsBeforeTheSessionOpens() {
        val store = MemorySyncStore()
        val run = run(store, script(range = ByteArray(10)))
        assertEquals(SessionOutcome.ABORTED, run.engine.outcome)
        assertTrue(store.sessions.isEmpty())
        assertNoAcks(run)
    }

    @Test
    fun closedGateCommitsAndDoesNotSendAck() {
        HistoryAckGate.debugRequested = true
        try {
            val store = MemorySyncStore()
            val run = run(store, script(), ackEnabled = { HistoryAckGate.maySend() })
            assertFalse(HistoryAckGate.maySend())
            assertFalse(HistoryAckGate.OFFSETS_CONFIRMED)
            assertEquals(1, store.chunks.size)
            assertNull(store.chunks.single().ackedAt)
            assertTrue(run.engine.events.any { it is SyncTrace.Commit })
            assertTrue(run.engine.events.none { it is SyncTrace.Ack })
            assertTrue(run.written.none { commandOf(it) == HistoryAckCommand.NUMBER })
            assertTrue(run.engine.isActive)
            assertEquals(SyncPhase.STREAM, run.engine.snapshot().phase)
            assertEquals(1, run.engine.snapshot().chunks)
            assertEquals(1, run.engine.snapshot().records)
            assertNull(run.engine.snapshot().outcome)
            assertNoDeniedCommands(run.written)
        } finally {
            HistoryAckGate.debugRequested = false
        }
    }

    @Test
    fun splitClockResponseStillCorrelates() {
        val store = MemorySyncStore()
        val clock = ManualMillis()
        val written = mutableListOf<ByteArray>()
        val engine = engine(store, clock, written, ackEnabled = { true })
        engine.start()
        val request = FrameCodec.decode(written.single()).let { (it as DecodeResult.Ok).frame }
        val response = FrameCodec.encodeCommandResponse(
            sequence = 1,
            command = ClockCommand.GET,
            originSequence = request.sequence,
            result = CommandResult.SUCCESS,
            body = ClockCommand.setBody(PHONE, PHONE_TICKS),
        )
        engine.onNotification("notify", response.copyOfRange(0, 7))
        assertTrue(engine.isActive)
        assertTrue(engine.events.none { it is SyncTrace.Opened })
        engine.onNotification("notify", response.copyOfRange(7, response.size))
        assertEquals(listOf(147, 34), sentCommands(engine))
    }

    @Test
    fun snapshotCountsRecordsBeforeTheChunkAndTheChunkBeforeTheOutcome() {
        val seen = mutableListOf<SyncSnapshot>()
        val run = run(
            MemorySyncStore(),
            script(),
            onProgress = { seen += it },
        )
        assertTrue(seen.any { it.phase == SyncPhase.CLOCK && it.outcome == null })
        assertTrue(seen.any { it.phase == SyncPhase.RANGE && it.outcome == null })
        assertTrue(seen.any { it.phase == SyncPhase.START && it.outcome == null })
        assertTrue(
            seen.any {
                it.phase == SyncPhase.STREAM && it.records == 1 && it.chunks == 0 && it.outcome == null
            },
        )
        assertTrue(
            seen.any {
                it.phase == SyncPhase.STREAM && it.records == 1 && it.chunks == 1 && it.outcome == null
            },
        )
        val done = run.engine.snapshot()
        assertEquals(SyncPhase.DONE, done.phase)
        assertEquals(1, done.chunks)
        assertEquals(1, done.records)
        assertEquals(SessionOutcome.COMPLETE, done.outcome)
        assertEquals(SessionOutcome.COMPLETE, seen.last().outcome)
    }

    @Test
    fun phoneTicksFollowTheHundredthsRule() {
        assertEquals(PhoneTime(1_700_000_000L, 0), phoneTimeFromUnixMillis(1_700_000_000_000L))
        assertEquals(PhoneTime(1, 16_384), phoneTimeFromUnixMillis(1_500))
        assertEquals(0, phoneTimeFromUnixMillis(0).ticks)
        assertTrue(phoneTimeFromUnixMillis(1_099).ticks <= ClockCommand.MAX_TICKS)
    }

    private fun script(
        strapUnixSeconds: Long = PHONE,
        range: ByteArray = ByteArray(DataRangeCommand.BODY_BYTES) { index -> if (index == 0) 1 else 0x22 },
        startResults: List<Int> = listOf(CommandResult.SUCCESS),
        startState: Int = 1,
        chunks: List<ScriptChunk> = listOf(ScriptChunk(sampleToken(), listOf(sampleRecord()))),
        stream: StreamKind = StreamKind.CHUNKS,
        floodCount: Int = HistoryLimits.MAX_RECORDS_WITHOUT_END,
    ): StrapScript = StrapScript(
        strapUnixSeconds = strapUnixSeconds,
        strapTicks = 100,
        range = range,
        startResults = startResults,
        startState = startState,
        chunks = chunks,
        stream = stream,
        floodCount = floodCount,
    )

    private fun emptyChunk(): ScriptChunk = ScriptChunk(byteArrayOf(4, 4, 4, 4, 4, 4, 4, 4), emptyList())

    private fun run(
        store: MemorySyncStore,
        script: StrapScript,
        clock: ManualMillis = ManualMillis(),
        ackEnabled: () -> Boolean = { true },
        onProgress: (SyncSnapshot) -> Unit = {},
    ): Attempt {
        val strap = FakeStrap(script)
        val written = mutableListOf<ByteArray>()
        val outbox = ArrayDeque<ByteArray>()
        val engine = engine(store, clock, written, ackEnabled, onProgress) { frame ->
            outbox += frame
            true
        }
        engine.start()
        var steps = 0
        while (engine.isActive && steps < 30_000) {
            steps += 1
            if (outbox.isNotEmpty()) {
                val frame = outbox.removeFirst()
                if (strap.connected) strap.accept(frame)
                continue
            }
            val incoming = strap.poll()
            if (incoming != null) {
                engine.onNotification("notify", incoming)
                continue
            }
            if (!strap.connected) {
                engine.onDisconnect()
                break
            }
            break
        }
        assertTrue(steps < 30_000)
        return Attempt(engine, store, strap, written)
    }

    private fun engine(
        store: MemorySyncStore,
        clock: ManualMillis,
        written: MutableList<ByteArray>,
        ackEnabled: () -> Boolean,
        onProgress: (SyncSnapshot) -> Unit = {},
        transmit: (ByteArray) -> Boolean = { true },
    ): HistorySyncEngine {
        return HistorySyncEngine(
            address = ADDRESS,
            store = store,
            phone = PhoneClock { PhoneTime(PHONE, PHONE_TICKS) },
            transmit = { frame ->
                written += frame.copyOf()
                transmit(frame)
            },
            ackEnabled = ackEnabled,
            nowMs = { clock.value },
            onProgress = onProgress,
        )
    }

    private fun sentCommands(engine: HistorySyncEngine): List<Int> =
        engine.events.filterIsInstance<SyncTrace.Sent>().map { it.command }

    private fun sentBody(engine: HistorySyncEngine, command: Int): ByteArray =
        engine.events.filterIsInstance<SyncTrace.Sent>().first { it.command == command }.body

    private fun commit(engine: HistorySyncEngine): SyncTrace.Commit =
        engine.events.filterIsInstance<SyncTrace.Commit>().single()

    private fun assertAckToken(run: Attempt, token: ByteArray) {
        val ack = run.engine.events.filterIsInstance<SyncTrace.Ack>().single()
        assertArrayEquals(token, ack.token)
        assertEquals(HistoryAckCommand.PREFIX, ack.body[0].toInt() and 0xFF)
        assertArrayEquals(token, ack.body.copyOfRange(1, 9))
        assertArrayEquals(token, run.strap.ackTokens.single())
        val frame = run.written.last { commandOf(it) == HistoryAckCommand.NUMBER }
        val decoded = FrameCodec.decode(frame) as DecodeResult.Ok
        assertEquals(PacketType.COMMAND, decoded.frame.packetType)
        assertArrayEquals(ack.body, decoded.frame.tail.copyOf(9))
    }

    private fun assertNoAcks(run: Attempt) {
        assertTrue(run.engine.events.none { it is SyncTrace.Ack })
        assertTrue(run.strap.ackTokens.isEmpty())
        assertTrue(run.written.none { commandOf(it) == HistoryAckCommand.NUMBER })
        assertTrue(run.store.chunks.all { it.ackedAt == null })
    }

    private fun assertAcksFollowCommits(events: List<SyncTrace>) {
        val seen = HashSet<String>()
        for (event in events) {
            when (event) {
                is SyncTrace.Commit -> seen += hex(event.token)
                is SyncTrace.Ack -> {
                    assertTrue(hex(event.token) in seen)
                    assertArrayEquals(event.token, event.body.copyOfRange(1, 9))
                }
                else -> Unit
            }
        }
    }

    private fun assertNoDeniedCommands(frames: List<ByteArray>) {
        val allowed = setOf(
            ClockCommand.GET,
            ClockCommand.SET,
            DataRangeCommand.NUMBER,
            HistoryStartCommand.NUMBER,
            HistoryAckCommand.NUMBER,
        )
        assertTrue(frames.isNotEmpty())
        for (frame in frames) {
            val command = commandOf(frame)
            assertTrue(command in allowed)
            assertFalse(command in DeniedCommands.NUMBERS)
            assertFalse(command in DeniedCommands.CERTIFICATE)
            assertFalse(command == 25 || command == 33)
        }
    }

    private fun commandOf(frame: ByteArray): Int {
        val decoded = FrameCodec.decode(frame) as DecodeResult.Ok
        return decoded.frame.command
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private class ManualMillis {
        var value: Long = 0
    }

    private class Attempt(
        val engine: HistorySyncEngine,
        val store: MemorySyncStore,
        val strap: FakeStrap,
        val written: List<ByteArray>,
    )

    private companion object {
        const val ADDRESS: String = "AA:BB:CC:DD:EE:FF"
        const val PHONE: Long = 1_700_000_000L
        const val PHONE_TICKS: Int = 50
    }
}
