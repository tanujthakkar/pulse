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
import app.pulse.wire.codec.HeaderWord
import app.pulse.wire.codec.HistoryAckCommand
import app.pulse.wire.codec.HistoryAckGate
import app.pulse.wire.codec.HistoryMetadata
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
        val token = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
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
        assertEquals(1, run.engine.snapshot().ackResult1)
        assertEquals(1, run.engine.snapshot().ackEligibleRecords)
        assertEquals(AckKind.RESULT, run.engine.snapshot().lastAckKind)
        assertEquals(CommandResult.SUCCESS, run.engine.snapshot().lastAckResult)
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
    fun duplicateEndIsOneCommitAndOneRetry() {
        val store = MemorySyncStore()
        val token = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        val run = run(
            store,
            script(chunks = listOf(ScriptChunk(token, listOf(sampleRecord()), extraEnds = 1))),
        )
        val commits = run.engine.events.filterIsInstance<SyncTrace.Commit>()
        assertEquals(1, commits.size)
        assertFalse(commits[0].duplicate)
        assertEquals(1, store.chunks.size)
        assertEquals(1, store.savedCounts().samples)
        assertEquals(1, store.savedCounts().chunks)
        val snap = run.engine.snapshot()
        assertEquals(2, snap.endsReceived)
        assertEquals(1, snap.chunks)
        assertEquals(0, snap.chunksDuplicate)
        assertEquals(1, snap.endsRetried)
        assertEquals(1, snap.savedSamples)
        val acks = run.engine.events.filterIsInstance<SyncTrace.Ack>()
        assertEquals(1, acks.size)
        assertArrayEquals(token, acks[0].token)
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
        val token = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        val record = sampleRecord()
        val first = run(store, script(chunks = listOf(ScriptChunk(token, listOf(record)))))
        assertEquals(PHONE, store.chunks.single().ackedAt)
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
        assertEquals(2, run.engine.snapshot().ackResult1)
        assertEquals(2, run.engine.snapshot().ackEligibleRecords)
        assertEquals(PHONE, store.chunks[0].ackedAt)
        assertEquals(PHONE, store.chunks[1].ackedAt)
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
    fun aBurstOfRecordsDoesNotPublishOncePerRecord() {
        val seen = mutableListOf<SyncSnapshot>()
        val run = run(
            MemorySyncStore(),
            script(stream = StreamKind.FLOOD, floodCount = 200),
            onProgress = { seen += it },
        )
        assertEquals(200, run.engine.snapshot().records)
        val during = seen.count { it.phase == SyncPhase.STREAM && it.records > 0 && it.outcome == null }
        assertTrue(seen.any { it.records == 1 && it.chunks == 0 })
        assertTrue(during < 20)
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
    fun allOnesEndIsNotStoredOrAcknowledgedAndALaterTokenIs() {
        val store = MemorySyncStore()
        val run = run(store, script(stream = StreamKind.SILENT), ackEnabled = { true })
        val real = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val ones = ByteArray(8) { 0xFF.toByte() }
        run.engine.onNotification("notify", metadata(HistoryMetadata.END, ones))
        assertTrue(store.chunks.isEmpty())
        assertEquals(1, run.engine.snapshot().endsRejected)
        assertEquals(0, run.engine.snapshot().chunks)
        assertTrue(run.written.none { commandOf(it) == HistoryAckCommand.NUMBER })
        run.engine.onNotification("notify", metadata(HistoryMetadata.START, null))
        run.engine.onNotification("notify", sampleRecord())
        run.engine.onNotification("notify", metadata(HistoryMetadata.END, real))
        run.engine.onNotification("notify", metadata(HistoryMetadata.END, real))
        assertEquals(1, store.chunks.size)
        assertArrayEquals(real, store.chunks.single().token)
        assertEquals(1, store.savedCounts().samples)
        assertEquals(1, run.engine.snapshot().chunks)
        assertEquals(1, run.engine.snapshot().endsRetried)
        assertEquals(1, run.engine.snapshot().savedSamples)
        assertEquals(1, run.engine.events.filterIsInstance<SyncTrace.Ack>().size)
        assertFalse(store.chunks.any { it.token.contentEquals(ones) })
        assertFalse(HistoryAckGate.maySend())
        assertFalse(HistoryAckGate.OFFSETS_CONFIRMED)
        run.engine.onNotification("notify", metadata(HistoryMetadata.COMPLETE, null))
        assertEquals(SessionOutcome.COMPLETE, run.engine.outcome)
    }

    @Test
    fun idleCompleteDoesNotFinishAnAttempt() {
        val written = mutableListOf<ByteArray>()
        val engine = engine(MemorySyncStore(), ManualMillis(), written, ackEnabled = { true })
        engine.onNotification("notify", shortComplete())
        assertNull(engine.outcome)
        assertFalse(engine.isActive)
        assertEquals(0, engine.snapshot().ignoredCompletes)
        assertTrue(written.isEmpty())
    }

    @Test
    fun completeBeforeStartDoesNotFinishTheAttempt() {
        val store = MemorySyncStore()
        val run = run(store, script(stream = StreamKind.SILENT), ackEnabled = { true })
        run.engine.onNotification("notify", shortComplete())
        assertTrue(run.engine.isActive)
        assertNull(run.engine.outcome)
        assertEquals(1, run.engine.snapshot().ignoredCompletes)
        assertTrue(store.chunks.isEmpty())
        run.engine.onNotification("notify", metadata(HistoryMetadata.START, null))
        run.engine.onNotification("notify", shortComplete())
        assertEquals(SessionOutcome.COMPLETE, run.engine.outcome)
        assertFalse(run.engine.snapshot().droppedUncommitted)
        assertTrue(run.written.none { commandOf(it) == HistoryAckCommand.NUMBER })
    }

    @Test
    fun watchdogAfterACommitKeepsTheSavedRows() {
        val clock = ManualMillis()
        val store = MemorySyncStore()
        val run = run(
            store,
            script(stream = StreamKind.SILENT),
            clock = clock,
            ackEnabled = { HistoryAckGate.maySend() },
        )
        val token = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9)
        run.engine.onNotification("notify", metadata(HistoryMetadata.START, null))
        run.engine.onNotification("notify", sampleRecord())
        run.engine.onNotification("notify", metadata(HistoryMetadata.END, token))
        assertEquals(1, store.savedCounts().samples)
        assertEquals(1, run.engine.snapshot().savedSamples)
        assertEquals(1, run.engine.snapshot().chunks)
        clock.value = HistoryLimits.WATCHDOG_MS
        assertTrue(run.engine.onWatchdog())
        assertEquals(SessionOutcome.WATCHDOG, run.engine.outcome)
        assertFalse(run.engine.snapshot().droppedUncommitted)
        assertEquals(1, run.engine.snapshot().savedSamples)
        assertEquals(1, run.engine.snapshot().savedChunks)
        assertTrue(run.engine.events.none { it is SyncTrace.DroppedChunk })
        assertTrue(run.written.none { commandOf(it) == HistoryAckCommand.NUMBER })
        assertFalse(HistoryAckGate.maySend())
        assertFalse(HistoryAckGate.OFFSETS_CONFIRMED)
    }

    @Test
    fun aResentEndDuringTransmitDoesNotSendASecondAck() {
        val store = MemorySyncStore()
        val token = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        val written = mutableListOf<ByteArray>()
        var reentered = false
        lateinit var engine: HistorySyncEngine
        engine = engine(store, ManualMillis(), written, ackEnabled = { true }) { frame ->
            val decoded = FrameCodec.decode(frame)
            if (!reentered &&
                decoded is DecodeResult.Ok &&
                decoded.frame.command == HistoryAckCommand.NUMBER
            ) {
                reentered = true
                engine.onNotification("reenter", metadata(HistoryMetadata.END, token))
            }
            true
        }
        val run = pump(engine, written, script(chunks = listOf(ScriptChunk(token, listOf(sampleRecord())))))
        assertTrue(reentered)
        assertEquals(SessionOutcome.COMPLETE, run.outcome)
        assertEquals(1, written.count { commandOf(it) == HistoryAckCommand.NUMBER })
        assertEquals(1, store.chunks.size)
        assertEquals(PHONE, store.chunks.single().ackedAt)
        assertFalse(HistoryAckGate.maySend())
    }

    @Test
    fun theNextChunkEndDuringTransmitGetsItsOwnAck() {
        val store = MemorySyncStore()
        val token = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        val other = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val written = mutableListOf<ByteArray>()
        var reentered = false
        lateinit var engine: HistorySyncEngine
        engine = engine(store, ManualMillis(), written, ackEnabled = { true }) { frame ->
            val decoded = FrameCodec.decode(frame)
            if (!reentered &&
                decoded is DecodeResult.Ok &&
                decoded.frame.command == HistoryAckCommand.NUMBER
            ) {
                reentered = true
                engine.onNotification("reenter", metadata(HistoryMetadata.END, other))
            }
            true
        }
        pump(engine, written, script(chunks = listOf(ScriptChunk(token, listOf(sampleRecord())))))
        assertTrue(reentered)
        assertEquals(2, written.count { commandOf(it) == HistoryAckCommand.NUMBER })
        assertEquals(2, store.chunks.size)
        assertEquals(listOf(token.toList(), other.toList()), store.chunks.map { it.token.toList() })
        assertFalse(HistoryAckGate.maySend())
        assertFalse(HistoryAckGate.OFFSETS_CONFIRMED)
    }

    @Test
    fun resultZeroDoesNotMarkAndIsNotRetriedOnThisAttempt() {
        val store = MemorySyncStore()
        val token = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        val run = run(
            store,
            script(
                chunks = listOf(ScriptChunk(token, listOf(sampleRecord()), extraEnds = 4)),
                ackResult = CommandResult.FAILURE,
            ),
        )
        assertTrue(run.engine.isActive)
        assertEquals(1, run.written.count { commandOf(it) == HistoryAckCommand.NUMBER })
        assertEquals(1, run.engine.snapshot().ackResult0)
        assertEquals(0, run.engine.snapshot().ackResult1)
        assertNull(store.chunks.single().ackedAt)
        assertEquals(AckKind.RESULT, run.engine.snapshot().lastAckKind)
        assertEquals(CommandResult.FAILURE, run.engine.snapshot().lastAckResult)
        run.engine.onDisconnect()
        val again = run(store, script(chunks = listOf(ScriptChunk(token, listOf(sampleRecord())))))
        assertEquals(SessionOutcome.COMPLETE, again.engine.outcome)
        assertEquals(PHONE, store.chunks.single().ackedAt)
        assertEquals(1, again.written.count { commandOf(it) == HistoryAckCommand.NUMBER })
    }

    @Test
    fun resultZeroDoesNotClearAnEarlierResult() {
        val store = MemorySyncStore()
        val token = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        val chunk = listOf(ScriptChunk(token, listOf(sampleRecord())))
        val first = run(store, script(chunks = chunk))
        assertEquals(PHONE, store.chunks.single().ackedAt)
        val second = run(store, script(chunks = chunk, ackResult = CommandResult.FAILURE))
        assertTrue(second.engine.isActive)
        assertEquals(1, second.engine.snapshot().ackResult0)
        assertEquals(PHONE, store.chunks.single().ackedAt)
        assertEquals(1, second.written.count { commandOf(it) == HistoryAckCommand.NUMBER })
        assertEquals(SessionOutcome.COMPLETE, first.engine.outcome)
    }

    @Test
    fun silenceIsNotMarkedAndTheNextAttemptMayTryOnce() {
        val clock = ManualMillis()
        val store = MemorySyncStore()
        val token = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        val run = run(
            store,
            script(chunks = listOf(ScriptChunk(token, listOf(sampleRecord()))), ackResult = null),
            clock = clock,
        )
        assertTrue(run.engine.isActive)
        assertEquals(1, run.written.count { commandOf(it) == HistoryAckCommand.NUMBER })
        assertEquals(AckKind.HANDED_OFF, run.engine.snapshot().lastAckKind)
        clock.value = HistoryLimits.WATCHDOG_MS
        assertTrue(run.engine.onWatchdog())
        assertEquals(1, run.engine.snapshot().ackUnanswered)
        assertEquals(AckKind.NO_RESPONSE, run.engine.snapshot().lastAckKind)
        assertNull(store.chunks.single().ackedAt)
        assertEquals(1, run.written.count { commandOf(it) == HistoryAckCommand.NUMBER })
        val again = run(store, script(chunks = listOf(ScriptChunk(token, listOf(sampleRecord())))))
        assertEquals(PHONE, store.chunks.single().ackedAt)
    }

    @Test
    fun pendingStaysOpenAndATimeoutDoesNotMark() {
        val clock = ManualMillis()
        val store = MemorySyncStore()
        val run = run(
            store,
            script(ackResult = CommandResult.PENDING),
            clock = clock,
        )
        assertEquals(1, run.engine.snapshot().ackPending)
        assertNull(store.chunks.single().ackedAt)
        clock.value = HistoryLimits.WATCHDOG_MS
        assertTrue(run.engine.onWatchdog())
        assertEquals(0, run.engine.snapshot().ackPending)
        assertEquals(1, run.engine.snapshot().ackUnanswered)
        assertNull(store.chunks.single().ackedAt)
        assertEquals(1, run.written.count { commandOf(it) == HistoryAckCommand.NUMBER })
    }

    @Test
    fun aRefusedWriteDoesNotMarkAndDoesNotFinishTheAttempt() {
        val store = MemorySyncStore()
        val token = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        val written = mutableListOf<ByteArray>()
        val outbox = ArrayDeque<ByteArray>()
        val engine = engine(store, ManualMillis(), written, ackEnabled = { true }) { frame ->
            val decoded = FrameCodec.decode(frame)
            val ack = decoded is DecodeResult.Ok && decoded.frame.command == HistoryAckCommand.NUMBER
            if (!ack) outbox += frame
            !ack
        }
        val strap = FakeStrap(script(chunks = listOf(ScriptChunk(token, listOf(sampleRecord())))))
        engine.start()
        var steps = 0
        while (engine.isActive && steps < 30_000) {
            steps += 1
            if (outbox.isNotEmpty()) {
                strap.accept(outbox.removeFirst())
                continue
            }
            val incoming = strap.poll() ?: break
            engine.onNotification("notify", incoming)
        }
        assertTrue(steps < 30_000)
        assertTrue(engine.isActive)
        assertEquals(1, engine.snapshot().ackRadioRefused)
        assertEquals(0, engine.events.filterIsInstance<SyncTrace.Ack>().size)
        assertNull(store.chunks.single().ackedAt)
        assertNull(engine.outcome)
        assertEquals(1, written.count { commandOf(it) == HistoryAckCommand.NUMBER })
    }

    @Test
    fun aMissingReadBackDoesNotSend() {
        val store = MemorySyncStore().also { it.hidePersistedAfter = 0 }
        val run = run(store, script())
        assertTrue(run.engine.isActive)
        assertEquals(1, store.chunks.size)
        assertNull(store.chunks.single().ackedAt)
        assertEquals(1, run.engine.snapshot().ackSkipped)
        assertTrue(run.engine.snapshot().ackNote!!.contains("read back"))
        assertTrue(run.written.none { commandOf(it) == HistoryAckCommand.NUMBER })
    }

    @Test
    fun aMarkFailureAfterResultOneDoesNotWriteAgain() {
        val store = MemorySyncStore().also { it.failMark = true }
        val run = run(store, script())
        assertEquals(SessionOutcome.COMPLETE, run.engine.outcome)
        assertEquals(1, run.engine.snapshot().ackResult1)
        assertNull(store.chunks.single().ackedAt)
        assertEquals(1, run.written.count { commandOf(it) == HistoryAckCommand.NUMBER })
    }

    @Test
    fun theWrongFirmwareAndTheOffSwitchSaveWithoutSending() {
        val wrong = run(MemorySyncStore(), script(), firmware = { "50.42.1.0" })
        assertTrue(wrong.engine.isActive)
        assertEquals(1, wrong.store.chunks.size)
        assertNull(wrong.store.chunks.single().ackedAt)
        assertTrue(wrong.engine.snapshot().ackNote!!.contains(HistoryAckFirmware.ALLOWED))
        assertTrue(wrong.written.none { commandOf(it) == HistoryAckCommand.NUMBER })

        val off = run(MemorySyncStore(), script(), ackEnabled = { false })
        assertTrue(off.engine.isActive)
        assertNull(off.store.chunks.single().ackedAt)
        assertTrue(off.engine.snapshot().ackNote!!.contains("off"))
        assertTrue(off.written.none { commandOf(it) == HistoryAckCommand.NUMBER })
    }

    @Test
    fun anAutomaticSyncWithTheSwitchOffSendsNoAck() {
        val off = run(MemorySyncStore(), script(), ackEnabled = { false })
        assertTrue(off.engine.isActive)
        assertEquals(1, off.store.chunks.size)
        assertNull(off.store.chunks.single().ackedAt)
        assertTrue(off.written.none { commandOf(it) == HistoryAckCommand.NUMBER })
    }

    @Test
    fun aSpecialControlWordIsStoredAndNotAcknowledged() {
        val token = sampleToken()
        val run = run(MemorySyncStore(), script(chunks = listOf(ScriptChunk(token, listOf(sampleRecord())))))
        assertTrue(run.engine.isActive)
        assertArrayEquals(token, run.store.chunks.single().token)
        assertNull(run.store.chunks.single().ackedAt)
        assertEquals(1, run.engine.snapshot().ackSkipped)
        assertTrue(run.written.none { commandOf(it) == HistoryAckCommand.NUMBER })
        assertFalse(HistoryAckGate.maySend())
    }

    @Test
    fun turningTheSwitchOffStopsTheNextChunkOnly() {
        var enabled = true
        val first = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        val second = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val run = run(
            MemorySyncStore(),
            script(
                chunks = listOf(
                    ScriptChunk(first, listOf(sampleRecord(recordIndex = 1))),
                    ScriptChunk(second, listOf(sampleRecord(recordIndex = 2))),
                ),
            ),
            ackEnabled = { enabled },
            onProgress = { snap -> if (snap.ackSent == 1) enabled = false },
        )
        assertTrue(run.engine.isActive)
        assertEquals(2, run.store.chunks.size)
        assertEquals(1, run.written.count { commandOf(it) == HistoryAckCommand.NUMBER })
        assertEquals(PHONE, run.store.chunks[0].ackedAt)
        assertNull(run.store.chunks[1].ackedAt)
        assertTrue(run.engine.snapshot().ackNote!!.contains("off"))
    }

    @Test
    fun disconnectDropsTheOpenChunkAndLeavesTheCommittedOne() {
        val store = MemorySyncStore()
        val kept = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        val open = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val run = run(store, script(stream = StreamKind.SILENT))
        run.engine.onNotification("notify", metadata(HistoryMetadata.START, null))
        run.engine.onNotification("notify", sampleRecord(recordIndex = 1))
        run.engine.onNotification("notify", metadata(HistoryMetadata.END, kept))
        run.engine.onNotification("notify", metadata(HistoryMetadata.START, null))
        run.engine.onNotification("notify", sampleRecord(recordIndex = 2))
        assertEquals(1, store.chunks.size)
        run.engine.onDisconnect()
        assertEquals(SessionOutcome.DISCONNECTED, run.engine.outcome)
        assertEquals(1, store.chunks.size)
        assertTrue(run.engine.snapshot().droppedUncommitted)
        assertEquals(1, run.written.count { commandOf(it) == HistoryAckCommand.NUMBER })
        assertArrayEquals(kept, store.chunks.single().token)
        assertFalse(store.chunks.any { it.token.contentEquals(open) })
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
        chunks: List<ScriptChunk> = listOf(ScriptChunk(byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2), listOf(sampleRecord()))),
        stream: StreamKind = StreamKind.CHUNKS,
        floodCount: Int = HistoryLimits.MAX_RECORDS_WITHOUT_END,
        ackResult: Int? = CommandResult.SUCCESS,
    ): StrapScript = StrapScript(
        strapUnixSeconds = strapUnixSeconds,
        strapTicks = 100,
        range = range,
        startResults = startResults,
        startState = startState,
        chunks = chunks,
        stream = stream,
        floodCount = floodCount,
        ackResult = ackResult,
    )

    private fun emptyChunk(): ScriptChunk = ScriptChunk(byteArrayOf(4, 4, 4, 4, 4, 4, 4, 4), emptyList())

    private fun metadata(kind: Int, token: ByteArray?): ByteArray {
        val body = ByteArray(18)
        if (token != null) token.copyInto(body, destinationOffset = 10)
        return FrameCodec.encode(
            headerWord = HeaderWord.COMMON_RESPONSE,
            packetType = PacketType.HISTORY_METADATA,
            sequence = 1,
            command = kind,
            body = body,
        )
    }

    /** Kind 3 with no token bytes, the short complete shape. */
    private fun shortComplete(): ByteArray {
        return FrameCodec.encode(
            headerWord = HeaderWord.COMMON_RESPONSE,
            packetType = PacketType.HISTORY_METADATA,
            sequence = 2,
            command = HistoryMetadata.COMPLETE,
            body = ByteArray(9),
        )
    }

    private fun run(
        store: MemorySyncStore,
        script: StrapScript,
        clock: ManualMillis = ManualMillis(),
        ackEnabled: () -> Boolean = { true },
        onProgress: (SyncSnapshot) -> Unit = {},
        firmware: () -> String? = { HistoryAckFirmware.ALLOWED },
    ): Attempt {
        val strap = FakeStrap(script)
        val written = mutableListOf<ByteArray>()
        val outbox = ArrayDeque<ByteArray>()
        val engine = engine(store, clock, written, ackEnabled, onProgress, firmware) { frame ->
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

    private fun pump(
        engine: HistorySyncEngine,
        written: MutableList<ByteArray>,
        script: StrapScript,
    ): HistorySyncEngine {
        val strap = FakeStrap(script)
        engine.start()
        var accepted = 0
        var steps = 0
        while (engine.isActive && steps < 30_000) {
            steps += 1
            if (accepted < written.size) {
                val frame = written[accepted]
                accepted += 1
                if (strap.connected) strap.accept(frame)
                continue
            }
            val incoming = strap.poll()
            if (incoming != null) {
                engine.onNotification("notify", incoming)
                continue
            }
            break
        }
        assertTrue(steps < 30_000)
        return engine
    }

    private fun engine(
        store: MemorySyncStore,
        clock: ManualMillis,
        written: MutableList<ByteArray>,
        ackEnabled: () -> Boolean,
        onProgress: (SyncSnapshot) -> Unit = {},
        firmware: () -> String? = { HistoryAckFirmware.ALLOWED },
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
            historyAckEnabled = ackEnabled,
            firmware = firmware,
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
