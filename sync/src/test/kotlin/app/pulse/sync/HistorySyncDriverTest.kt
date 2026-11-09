package app.pulse.sync

import app.pulse.testing.fakestrap.FakeStrap
import app.pulse.testing.fakestrap.ScriptChunk
import app.pulse.testing.fakestrap.StrapScript
import app.pulse.testing.fakestrap.StreamKind
import app.pulse.testing.fakestrap.sampleRecord
import app.pulse.testing.fakestrap.sampleToken
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
import app.pulse.wire.codec.PacketType
import java.util.ArrayDeque
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistorySyncDriverTest {
    @Test
    fun syncNowWithoutALinkDoesNotWrite() {
        val link = FakeHistoryLink()
        val driver = driver(link)
        driver.syncNow()
        assertEquals("Link a strap before syncing.", driver.status)
        assertFalse(driver.running)
        assertTrue(link.written.isEmpty())
    }

    @Test
    fun syncNowSendsClockGetAndADropStopsTheAttempt() {
        val link = FakeHistoryLink(address = "AA:BB:CC:DD:EE:FF")
        val driver = driver(link)
        driver.syncNow()
        assertEquals("Syncing", driver.status)
        assertTrue(driver.running)
        val frame = FrameCodec.decode(link.written.single()) as DecodeResult.Ok
        assertEquals(ClockCommand.GET, frame.frame.command)
        driver.syncNow()
        assertEquals(1, link.written.size)
        link.down()
        assertEquals("Sync stopped.", driver.status)
        assertFalse(driver.running)
        assertEquals(SessionOutcome.DISCONNECTED, driver.activity.outcome)
        assertFalse(HistoryAckGate.maySend())
    }

    @Test
    fun syncNowWithoutALinkKeepsTheLastOutcomeAndReportsTheBlock() {
        val driver = driver(FakeHistoryLink())
        driver.syncNow()
        assertEquals("Link a strap before syncing.", driver.activity.blocked)
        assertFalse(driver.activity.running)
        assertNull(driver.activity.outcome)
    }

    @Test
    fun idleSavedCountsComeFromTheStore() {
        val store = MemorySyncStore()
        val session = store.openSession(
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            startedUnixSeconds = 1L,
            startedSubsecondTicks = null,
            rangeSnapshot = ByteArray(DataRangeCommand.BODY_BYTES),
        )
        store.commitChunk(
            sessionId = session,
            frames = emptyList(),
            r18Rows = emptyList(),
            archived = emptyList(),
            token = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
        )
        val driver = HistorySyncDriver(
            link = FakeHistoryLink(),
            store = store,
            phone = PhoneClock { PhoneTime(1L, 0) },
            schedule = HistoryScheduler { _, _ -> HistorySubscription { } },
            nowMs = { 0L },
        )
        driver.refreshSaved()
        assertEquals(0, driver.activity.savedSamples)
        assertEquals(1, driver.activity.savedChunks)
        assertNull(driver.activity.outcome)
        assertNull(driver.activity.ackSamples)
        assertEquals(AckPhase.OFF, driver.activity.ackPhase)
        assertFalse(HistoryAckGate.maySend())
    }

    @Test
    fun prearmedConsentSendsTheStoredTokenOnceDuringTheAttemptThenStaysOff() {
        val token = sendableToken()
        val harness = harness()
        assertTrue(harness.driver.armOneShot(OneShotAck.FIRMWARE))
        assertFalse(harness.driver.armOneShot(OneShotAck.FIRMWARE))
        assertEquals(AckPhase.ARMED, harness.driver.activity.ackPhase)
        assertEquals(0, harness.link.ackAttempts)
        harness.driver.syncNow()
        assertEquals(AckPhase.WAITING, harness.driver.activity.ackPhase)
        harness.link.pump { harness.driver.running }
        assertFalse(harness.driver.running)
        assertEquals(SessionOutcome.COMPLETE, harness.driver.activity.outcome)
        assertEquals(1, harness.link.ackAttempts)
        val decoded = FrameCodec.decode(harness.ackFrames().single()) as DecodeResult.Ok
        assertEquals(PacketType.COMMAND, decoded.frame.packetType)
        assertEquals(HeaderWord.COMMON_REQUEST, decoded.frame.headerWord)
        assertEquals(HistoryAckCommand.NUMBER, decoded.frame.command)
        assertEquals(4, decoded.frame.sequence)
        assertEquals(HistoryAckCommand.PREFIX, decoded.frame.tail[0].toInt() and 0xFF)
        assertArrayEquals(token, decoded.frame.tail.copyOfRange(1, 9))
        assertEquals(AckPhase.RESULT, harness.driver.activity.ackPhase)
        assertEquals(CommandResult.SUCCESS, harness.driver.activity.ackResult)
        assertEquals(1, harness.driver.activity.ackSamples)
        assertEquals(1_700_000_000L, harness.store.chunks.single().ackedAt)
        assertFalse(harness.driver.cancelOneShot())
        harness.driver.syncNow()
        harness.link.pump { harness.driver.running }
        assertEquals(1, harness.link.ackAttempts)
        assertFalse(HistoryAckGate.maySend())
        assertFalse(HistoryAckGate.OFFSETS_CONFIRMED)
        assertFalse(HistoryAckGate.debugRequested)
        assertFalse(DeniedCommands.allows(HistoryAckCommand.NUMBER))
    }

    @Test
    fun anUnarmedSyncDoesNotSend() {
        val harness = harness()
        harness.driver.syncNow()
        harness.link.pump { harness.driver.running }
        assertTrue(harness.driver.running)
        assertEquals(0, harness.link.ackAttempts)
        assertEquals(AckPhase.OFF, harness.driver.activity.ackPhase)
        harness.finishQuiet()
        assertEquals(1, harness.store.chunks.size)
        assertNull(harness.store.chunks.single().ackedAt)
        assertFalse(HistoryAckGate.maySend())
    }

    @Test
    fun theWrongFirmwareDoesNotArmAndABlockedTapKeepsConsent() {
        val harness = harness()
        assertFalse(harness.driver.armOneShot("50.42.1.0"))
        assertEquals(AckPhase.OFF, harness.driver.activity.ackPhase)
        assertTrue(harness.driver.activity.ackNote!!.contains(OneShotAck.FIRMWARE))
        harness.driver.syncNow()
        harness.link.pump { harness.driver.running }
        assertEquals(0, harness.link.ackAttempts)
        harness.finishQuiet()

        val kept = harness()
        assertTrue(kept.driver.armOneShot(OneShotAck.FIRMWARE))
        kept.link.address = null
        kept.driver.syncNow()
        assertEquals(AckPhase.ARMED, kept.driver.activity.ackPhase)
        assertEquals("Link a strap before syncing.", kept.driver.activity.blocked)
        kept.link.address = "AA:BB:CC:DD:EE:FF"
        kept.driver.syncNow()
        kept.link.pump { kept.driver.running }
        assertEquals(1, kept.link.ackAttempts)
        assertEquals(AckPhase.RESULT, kept.driver.activity.ackPhase)
    }

    @Test
    fun cancelBeforeTheChunkSendsNothing() {
        val harness = harness()
        assertTrue(harness.driver.armOneShot(OneShotAck.FIRMWARE))
        harness.driver.syncNow()
        assertEquals(AckPhase.WAITING, harness.driver.activity.ackPhase)
        assertTrue(harness.driver.cancelOneShot())
        harness.link.pump { harness.driver.running }
        assertEquals(0, harness.link.ackAttempts)
        assertEquals(AckPhase.OFF, harness.driver.activity.ackPhase)
        assertTrue(harness.driver.activity.ackNote!!.contains("cancelled"))
        assertNull(harness.store.chunks.single().ackedAt)
    }

    @Test
    fun commitFailureAndAnUnverifiedRowSendNothing() {
        val failing = MemorySyncStore().also { it.failCommit = true }
        val aborted = harness(store = failing)
        assertTrue(aborted.driver.armOneShot(OneShotAck.FIRMWARE))
        aborted.driver.syncNow()
        aborted.link.pump { aborted.driver.running }
        assertEquals(SessionOutcome.ABORTED, aborted.driver.activity.outcome)
        assertEquals(0, aborted.link.ackAttempts)
        assertTrue(failing.chunks.isEmpty())
        assertEquals(AckPhase.OFF, aborted.driver.activity.ackPhase)
        assertTrue(aborted.driver.activity.ackNote!!.contains("Nothing was sent"))

        val hidden = MemorySyncStore().also { it.hidePersistedAfter = 1 }
        val unverified = harness(store = hidden)
        assertTrue(unverified.driver.armOneShot(OneShotAck.FIRMWARE))
        unverified.driver.syncNow()
        unverified.link.pump { unverified.driver.running }
        assertEquals(0, unverified.link.ackAttempts)
        assertEquals(AckPhase.REFUSED, unverified.driver.activity.ackPhase)
        assertTrue(unverified.driver.activity.ackNote!!.contains("could not be verified"))
        assertNull(hidden.chunks.single().ackedAt)
        unverified.finishQuiet()
        assertEquals(0, unverified.link.ackAttempts)
    }

    @Test
    fun aRefusedWriteIsConsumedAndALaterResponseIsNotSuccess() {
        val harness = harness()
        harness.link.rejectAck = true
        assertTrue(harness.driver.armOneShot(OneShotAck.FIRMWARE))
        harness.driver.syncNow()
        harness.link.pump { harness.driver.running }
        assertEquals(1, harness.link.ackAttempts)
        assertTrue(harness.ackFrames().isEmpty())
        assertEquals(AckPhase.RADIO_REFUSED, harness.driver.activity.ackPhase)
        assertNull(harness.driver.activity.ackResult)
        assertNull(harness.store.chunks.single().ackedAt)
        repeat(4) { origin ->
            harness.link.deliver(
                FrameCodec.encodeCommandResponse(
                    sequence = 30 + origin,
                    command = HistoryAckCommand.NUMBER,
                    originSequence = origin,
                    result = CommandResult.SUCCESS,
                ),
            )
        }
        assertEquals(AckPhase.RADIO_REFUSED, harness.driver.activity.ackPhase)
        assertNull(harness.driver.activity.ackResult)
        assertEquals(1, harness.link.ackAttempts)
        harness.finishQuiet()
        assertEquals(SessionOutcome.WATCHDOG, harness.driver.activity.outcome)
        assertEquals(AckPhase.RADIO_REFUSED, harness.driver.activity.ackPhase)
        assertFalse(HistoryAckGate.maySend())
    }

    @Test
    fun silencePendingAndFailureStayDistinctFromTheWatchdog() {
        val silent = harness(ackResult = null)
        assertTrue(silent.driver.armOneShot(OneShotAck.FIRMWARE))
        silent.driver.syncNow()
        silent.link.pump { silent.driver.running }
        assertTrue(silent.driver.running)
        assertEquals(AckPhase.HANDED_OFF, silent.driver.activity.ackPhase)
        assertNull(silent.driver.activity.ackResult)
        silent.finishQuiet()
        assertEquals(SessionOutcome.WATCHDOG, silent.driver.activity.outcome)
        assertEquals(AckPhase.NO_RESPONSE, silent.driver.activity.ackPhase)
        assertNull(silent.driver.activity.ackNote)
        assertEquals(1, silent.link.ackAttempts)

        val failed = harness(ackResult = CommandResult.FAILURE)
        assertTrue(failed.driver.armOneShot(OneShotAck.FIRMWARE))
        failed.driver.syncNow()
        failed.link.pump { failed.driver.running }
        assertEquals(AckPhase.RESULT, failed.driver.activity.ackPhase)
        assertEquals(CommandResult.FAILURE, failed.driver.activity.ackResult)
        assertEquals(1, failed.link.ackAttempts)
        assertTrue(failed.driver.running)

        val pending = harness(ackResult = CommandResult.PENDING)
        assertTrue(pending.driver.armOneShot(OneShotAck.FIRMWARE))
        pending.driver.syncNow()
        pending.link.pump { pending.driver.running }
        assertEquals(AckPhase.PENDING, pending.driver.activity.ackPhase)
        assertEquals(CommandResult.PENDING, pending.driver.activity.ackResult)
        pending.finishQuiet()
        assertEquals(SessionOutcome.WATCHDOG, pending.driver.activity.outcome)
        assertEquals(AckPhase.NO_RESPONSE, pending.driver.activity.ackPhase)
        assertTrue(pending.driver.activity.ackNote!!.contains("pending"))
        assertTrue(pending.driver.activity.ackNote!!.contains("not success"))
        assertEquals(1, pending.link.ackAttempts)
    }

    @Test
    fun resendsASecondChunkAndAReentrantEndDoNotWriteTwice() {
        val token = sendableToken()
        val resent = harness(
            chunks = listOf(ScriptChunk(token, listOf(sampleRecord()), extraEnds = 4)),
        )
        assertTrue(resent.driver.armOneShot(OneShotAck.FIRMWARE))
        resent.driver.syncNow()
        resent.link.pump { resent.driver.running }
        assertEquals(1, resent.link.ackAttempts)
        assertEquals(5, resent.driver.activity.endsReceived)
        assertEquals(4, resent.driver.activity.endsRetried)
        assertEquals(AckPhase.RESULT, resent.driver.activity.ackPhase)
        assertArrayEquals(token, (FrameCodec.decode(resent.ackFrames().single()) as DecodeResult.Ok).frame.tail.copyOfRange(1, 9))

        val second = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val multi = harness(
            chunks = listOf(
                ScriptChunk(token, listOf(sampleRecord())),
                ScriptChunk(second, listOf(sampleRecord())),
            ),
        )
        assertTrue(multi.driver.armOneShot(OneShotAck.FIRMWARE))
        multi.driver.syncNow()
        multi.link.pump { multi.driver.running }
        assertEquals(1, multi.link.ackAttempts)
        assertEquals(2, multi.store.chunks.size)
        assertNotNull(multi.store.chunks.first { it.token.contentEquals(token) }.ackedAt)
        assertNull(multi.store.chunks.first { it.token.contentEquals(second) }.ackedAt)
        multi.finishQuiet()
        assertEquals(1, multi.link.ackAttempts)

        val raced = harness()
        raced.link.onAckWrite = { raced.link.deliver(endFrame(second)) }
        assertTrue(raced.driver.armOneShot(OneShotAck.FIRMWARE))
        raced.driver.syncNow()
        raced.link.pump { raced.driver.running }
        assertEquals(1, raced.link.ackAttempts)
        assertEquals(2, raced.store.chunks.size)
        assertEquals(AckPhase.RESULT, raced.driver.activity.ackPhase)
    }

    @Test
    fun disconnectClearsConsentAndANewProcessDoesNotRestoreIt() {
        val armed = harness()
        assertTrue(armed.driver.armOneShot(OneShotAck.FIRMWARE))
        armed.driver.dropOneShot()
        assertEquals(AckPhase.OFF, armed.driver.activity.ackPhase)
        assertTrue(armed.driver.activity.ackNote!!.contains("link dropped"))
        armed.driver.syncNow()
        armed.link.pump { armed.driver.running }
        assertEquals(0, armed.link.ackAttempts)

        val live = harness()
        assertTrue(live.driver.armOneShot(OneShotAck.FIRMWARE))
        live.driver.syncNow()
        assertEquals(AckPhase.WAITING, live.driver.activity.ackPhase)
        live.link.down()
        assertEquals(SessionOutcome.DISCONNECTED, live.driver.activity.outcome)
        assertEquals(AckPhase.OFF, live.driver.activity.ackPhase)
        assertEquals(0, live.link.ackAttempts)

        val store = MemorySyncStore()
        val first = harness(store = store)
        assertTrue(first.driver.armOneShot(OneShotAck.FIRMWARE))
        first.driver.syncNow()
        first.link.pump { first.driver.running }
        assertEquals(1, first.link.ackAttempts)
        assertNotNull(store.chunks.single().ackedAt)
        val reborn = harness(store = store)
        assertEquals(AckPhase.OFF, reborn.driver.activity.ackPhase)
        reborn.driver.syncNow()
        reborn.link.pump { reborn.driver.running }
        assertEquals(0, reborn.link.ackAttempts)
        reborn.finishQuiet()
        val again = harness(store = store)
        assertTrue(again.driver.armOneShot(OneShotAck.FIRMWARE))
        again.driver.syncNow()
        again.link.pump { again.driver.running }
        assertEquals(1, again.link.ackAttempts)
        assertEquals(1, store.chunks.size)
        assertTrue(again.driver.activity.chunksDuplicate >= 1)
    }

    @Test
    fun aStaleChunkASpecialTokenAndAFailedMarkDoNotSendASecondWrite() {
        val store = MemorySyncStore()
        val seeded = harness(store = store)
        seeded.driver.syncNow()
        seeded.link.pump { seeded.driver.running }
        seeded.finishQuiet()
        assertEquals(1, store.chunks.size)
        assertNull(store.chunks.single().ackedAt)
        val quiet = harness(store = store, stream = StreamKind.SILENT)
        assertTrue(quiet.driver.armOneShot(OneShotAck.FIRMWARE))
        quiet.driver.syncNow()
        quiet.link.pump { quiet.driver.running }
        quiet.finishQuiet()
        assertEquals(0, quiet.link.ackAttempts)
        assertNull(store.chunks.single().ackedAt)
        assertTrue(quiet.driver.activity.ackNote!!.contains("Nothing was sent"))

        val special = harness(chunks = listOf(ScriptChunk(sampleToken(), listOf(sampleRecord()))))
        assertTrue(special.driver.armOneShot(OneShotAck.FIRMWARE))
        special.driver.syncNow()
        special.link.pump { special.driver.running }
        assertEquals(0, special.link.ackAttempts)
        assertEquals(AckPhase.REFUSED, special.driver.activity.ackPhase)
        assertTrue(special.driver.activity.ackNote!!.contains("not acknowledged"))
        assertEquals(1, special.store.chunks.size)
        assertNull(special.store.chunks.single().ackedAt)

        val ones = harness(chunks = listOf(ScriptChunk(ByteArray(8) { 0xFF.toByte() }, listOf(sampleRecord()))))
        assertTrue(ones.driver.armOneShot(OneShotAck.FIRMWARE))
        ones.driver.syncNow()
        ones.link.pump { ones.driver.running }
        ones.finishQuiet()
        assertEquals(0, ones.link.ackAttempts)
        assertTrue(ones.store.chunks.isEmpty())
        assertTrue(ones.driver.activity.ackNote!!.contains("Nothing was sent"))

        val marked = MemorySyncStore().also { it.failMark = true }
        val bookkeeping = harness(store = marked)
        assertTrue(bookkeeping.driver.armOneShot(OneShotAck.FIRMWARE))
        bookkeeping.driver.syncNow()
        bookkeeping.link.pump { bookkeeping.driver.running }
        assertEquals(SessionOutcome.COMPLETE, bookkeeping.driver.activity.outcome)
        assertEquals(1, bookkeeping.link.ackAttempts)
        assertEquals(AckPhase.RESULT, bookkeeping.driver.activity.ackPhase)
        assertNull(marked.chunks.single().ackedAt)
        assertFalse(HistoryAckGate.OFFSETS_CONFIRMED)
    }

    @Test
    fun countsAdvanceWhileTheAckGateStaysClosed() {
        val strap = FakeStrap(StrapScript())
        val link = ScriptedHistoryLink(strap)
        val seen = mutableListOf<SyncActivity>()
        val driver = driver(link, phone = PhoneClock { PhoneTime(1_700_000_000L, 7) })
        driver.onChange = { seen += driver.activity }
        driver.syncNow()
        link.pump { driver.running }
        assertTrue(driver.running)
        assertEquals("Syncing", driver.status)
        assertEquals(SyncPhase.STREAM, driver.activity.phase)
        assertEquals(1, driver.activity.chunks)
        assertEquals(1, driver.activity.records)
        assertNull(driver.activity.outcome)
        assertTrue(seen.any { it.running && it.records == 1 && it.chunks == 0 })
        assertTrue(seen.any { it.running && it.records == 1 && it.chunks == 1 })
        assertTrue(link.written.none { commandOf(it) == HistoryAckCommand.NUMBER })
        assertFalse(HistoryAckGate.maySend())

        link.down()
        assertEquals("Sync stopped.", driver.status)
        assertFalse(driver.running)
        assertEquals(SessionOutcome.DISCONNECTED, driver.activity.outcome)
        assertEquals(SyncPhase.DONE, driver.activity.phase)
        assertEquals(1, driver.activity.chunks)
        assertEquals(1, driver.activity.records)
        assertEquals(PhoneTime(1_700_000_000L, 7), driver.activity.finishedAt)

        driver.syncNow()
        assertTrue(driver.running)
        assertEquals(0, driver.activity.chunks)
        assertEquals(0, driver.activity.records)
        assertNull(driver.activity.outcome)
        assertNull(driver.activity.finishedAt)
        assertEquals(0, link.written.count { commandOf(it) == HistoryAckCommand.NUMBER })
    }

    @Test
    fun anEnabledAckFinishesWithTheCompleteOutcome() {
        val strap = FakeStrap(StrapScript())
        val link = ScriptedHistoryLink(strap)
        val driver = driver(
            link,
            ackEnabled = { true },
            phone = PhoneClock { PhoneTime(1_700_000_100L, 0) },
        )
        driver.syncNow()
        link.pump { driver.running }
        assertFalse(driver.running)
        assertEquals("Sync finished.", driver.status)
        assertEquals(SessionOutcome.COMPLETE, driver.activity.outcome)
        assertEquals(SyncPhase.DONE, driver.activity.phase)
        assertEquals(1, driver.activity.chunks)
        assertEquals(1, driver.activity.records)
        assertEquals(PhoneTime(1_700_000_100L, 0), driver.activity.finishedAt)
        assertTrue(link.written.any { commandOf(it) == HistoryAckCommand.NUMBER })
        assertFalse(HistoryAckGate.maySend())
    }

    private fun driver(
        link: HistoryLink,
        ackEnabled: () -> Boolean = { HistoryAckGate.maySend() },
        phone: PhoneClock = PhoneClock { PhoneTime(1_700_000_000L, 0) },
    ): HistorySyncDriver {
        return HistorySyncDriver(
            link = link,
            store = MemorySyncStore(),
            phone = phone,
            schedule = HistoryScheduler { _, _ ->
                HistorySubscription { }
            },
            ackEnabled = ackEnabled,
            nowMs = { 0L },
        )
    }

    private fun sendableToken(): ByteArray = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)

    private fun harness(
        store: MemorySyncStore = MemorySyncStore(),
        chunks: List<ScriptChunk> = listOf(ScriptChunk(sendableToken(), listOf(sampleRecord()))),
        stream: StreamKind = StreamKind.CHUNKS,
        ackResult: Int? = CommandResult.SUCCESS,
    ): OneShotHarness {
        val strap = FakeStrap(
            StrapScript(chunks = chunks, stream = stream, ackResult = ackResult),
        )
        val link = ScriptedHistoryLink(strap)
        var now = 0L
        val tasks = mutableListOf<() -> Unit>()
        val driver = HistorySyncDriver(
            link = link,
            store = store,
            phone = PhoneClock { PhoneTime(1_700_000_000L, 0) },
            schedule = HistoryScheduler { _, task ->
                tasks += task
                HistorySubscription { }
            },
            nowMs = { now },
        )
        return OneShotHarness(driver, link, store, tasks) { now = it }
    }

    private fun endFrame(token: ByteArray): ByteArray {
        val body = ByteArray(18)
        token.copyInto(body, destinationOffset = 10)
        return FrameCodec.encode(
            headerWord = HeaderWord.COMMON_RESPONSE,
            packetType = PacketType.HISTORY_METADATA,
            sequence = 1,
            command = HistoryMetadata.END,
            body = body,
        )
    }

    private inner class OneShotHarness(
        val driver: HistorySyncDriver,
        val link: ScriptedHistoryLink,
        val store: MemorySyncStore,
        private val tasks: List<() -> Unit>,
        private val setNow: (Long) -> Unit,
    ) {
        fun finishQuiet() {
            setNow(HistoryLimits.WATCHDOG_MS)
            tasks.last().invoke()
            assertFalse(driver.running)
        }

        fun ackFrames(): List<ByteArray> = link.written.filter { commandOf(it) == HistoryAckCommand.NUMBER }
    }

    private fun commandOf(frame: ByteArray): Int {
        val decoded = FrameCodec.decode(frame) as DecodeResult.Ok
        return decoded.frame.command
    }
}

private class ScriptedHistoryLink(
    private val strap: FakeStrap,
    override var address: String? = "AA:BB:CC:DD:EE:FF",
) : HistoryLink {
    val written = mutableListOf<ByteArray>()
    private val outbox = ArrayDeque<ByteArray>()
    private var onNotification: (String, ByteArray) -> Unit = { _, _ -> }
    private var onDown: () -> Unit = {}
    private var inAckHook = false

    var rejectWrite: Boolean = false
    var rejectAck: Boolean = false
    var ackAttempts: Int = 0
    var onAckWrite: (() -> Unit)? = null

    override fun write(frame: ByteArray): Boolean {
        val command = commandOrNull(frame)
        if (command == HistoryAckCommand.NUMBER) {
            ackAttempts += 1
            if (!inAckHook) {
                inAckHook = true
                onAckWrite?.invoke()
                inAckHook = false
            }
            if (rejectAck) return false
        }
        if (rejectWrite) return false
        written += frame.copyOf()
        outbox += frame
        return true
    }

    fun deliver(bytes: ByteArray) {
        onNotification("inject", bytes)
    }

    private fun commandOrNull(frame: ByteArray): Int? {
        val decoded = FrameCodec.decode(frame)
        return if (decoded is DecodeResult.Ok) decoded.frame.command else null
    }

    override fun listen(
        onNotification: (source: String, bytes: ByteArray) -> Unit,
        onDown: () -> Unit,
    ): HistorySubscription {
        this.onNotification = onNotification
        this.onDown = onDown
        return HistorySubscription { }
    }

    fun pump(still: () -> Boolean) {
        var steps = 0
        while (still() && steps < 30_000) {
            steps += 1
            if (outbox.isNotEmpty()) {
                val frame = outbox.removeFirst()
                if (strap.connected) strap.accept(frame)
                continue
            }
            val incoming = strap.poll()
            if (incoming != null) {
                onNotification("notify", incoming)
                continue
            }
            if (!strap.connected) {
                onDown()
                break
            }
            break
        }
        assertTrue(steps < 30_000)
    }

    fun down() {
        onDown()
    }
}

private class FakeHistoryLink(
    override var address: String? = null,
) : HistoryLink {
    val written = mutableListOf<ByteArray>()
    private var onDown: () -> Unit = {}

    override fun write(frame: ByteArray): Boolean {
        written += frame.copyOf()
        return true
    }

    override fun listen(
        onNotification: (source: String, bytes: ByteArray) -> Unit,
        onDown: () -> Unit,
    ): HistorySubscription {
        this.onDown = onDown
        return HistorySubscription { }
    }

    fun down() {
        onDown()
    }
}
