package app.pulse.sync

import app.pulse.testing.fakestrap.FakeStrap
import app.pulse.testing.fakestrap.ScriptChunk
import app.pulse.testing.fakestrap.StrapScript
import app.pulse.testing.fakestrap.StreamKind
import app.pulse.testing.fakestrap.sampleRecord
import app.pulse.wire.codec.ClockCommand
import app.pulse.wire.codec.CommandResult
import app.pulse.wire.codec.DataRangeCommand
import app.pulse.wire.codec.DecodeResult
import app.pulse.wire.codec.FrameCodec
import app.pulse.wire.codec.HistoryAckCommand
import app.pulse.wire.codec.HistoryAckGate
import java.util.ArrayDeque
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistorySyncDriverTest {
    @Test
    fun syncNowWithoutALinkDoesNotWrite() {
        val link = FakeHistoryLink()
        val driver = driver(link)
        assertFalse(driver.syncNow())
        assertEquals("Link a strap before syncing.", driver.status)
        assertFalse(driver.running)
        assertTrue(link.written.isEmpty())
    }

    @Test
    fun syncNowSendsClockGetAndADropStopsTheAttempt() {
        val link = FakeHistoryLink(address = "AA:BB:CC:DD:EE:FF")
        val driver = driver(link)
        assertTrue(driver.syncNow())
        assertEquals("Syncing", driver.status)
        assertTrue(driver.running)
        val frame = FrameCodec.decode(link.written.single()) as DecodeResult.Ok
        assertEquals(ClockCommand.GET, frame.frame.command)
        assertFalse(driver.syncNow())
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
        assertFalse(driver.syncNow())
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
        assertFalse(driver.activity.historyAckEnabled)
        assertEquals(0, driver.activity.ackSent)
        assertFalse(HistoryAckGate.maySend())
    }

    @Test
    fun theSwitchIsVisibleAndAResultIsCopiedOntoTheActivity() {
        val harness = harness()
        harness.driver.setAckEnabled(false)
        assertFalse(harness.driver.activity.historyAckEnabled)
        harness.driver.setAckEnabled(true)
        assertTrue(harness.driver.activity.historyAckEnabled)
        harness.driver.syncNow()
        harness.link.pump { harness.driver.running }
        assertEquals(SessionOutcome.COMPLETE, harness.driver.activity.outcome)
        assertEquals(1, harness.driver.activity.ackResult1)
        assertEquals(1, harness.driver.activity.ackSent)
        assertEquals(1_700_000_000L, harness.store.chunks.single().ackedAt)
        assertEquals(AckKind.RESULT, harness.driver.activity.lastAckKind)
        assertFalse(HistoryAckGate.maySend())
    }

    @Test
    fun resultZeroAndSilenceStayOnTheActivityWithoutASecondWrite() {
        val failed = harness(ackResult = CommandResult.FAILURE)
        failed.driver.syncNow()
        failed.link.pump { failed.driver.running }
        assertTrue(failed.driver.running)
        assertEquals(1, failed.driver.activity.ackResult0)
        assertEquals(0, failed.driver.activity.ackResult1)
        assertNull(failed.store.chunks.single().ackedAt)
        assertEquals(1, failed.link.ackAttempts)

        val silent = harness(ackResult = null)
        silent.driver.syncNow()
        silent.link.pump { silent.driver.running }
        assertEquals(AckKind.HANDED_OFF, silent.driver.activity.lastAckKind)
        assertEquals(1, silent.link.ackAttempts)
        silent.finishQuiet()
        assertEquals(1, silent.driver.activity.ackUnanswered)
        assertEquals(AckKind.NO_RESPONSE, silent.driver.activity.lastAckKind)
        assertNull(silent.store.chunks.single().ackedAt)
        assertEquals(1, silent.link.ackAttempts)
    }

    @Test
    fun aRefusedWriteAndTheWrongFirmwareAreReportedAndNotRetried() {
        val refused = harness()
        refused.link.rejectAck = true
        refused.driver.syncNow()
        refused.link.pump { refused.driver.running }
        assertTrue(refused.driver.running)
        assertEquals(1, refused.driver.activity.ackRadioRefused)
        assertEquals(AckKind.RADIO_REFUSED, refused.driver.activity.lastAckKind)
        assertNull(refused.store.chunks.single().ackedAt)
        assertEquals(1, refused.link.ackAttempts)

        val wrong = harness(firmware = "50.42.1.0")
        wrong.driver.syncNow()
        wrong.link.pump { wrong.driver.running }
        assertTrue(wrong.driver.running)
        assertEquals(0, wrong.link.ackAttempts)
        assertEquals(1, wrong.store.chunks.size)
        assertTrue(wrong.driver.activity.ackNote!!.contains(HistoryAckFirmware.ALLOWED))
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
        val strap = FakeStrap(
            StrapScript(chunks = listOf(ScriptChunk(sendableToken(), listOf(sampleRecord())))),
        )
        val link = ScriptedHistoryLink(strap)
        val driver = driver(
            link,
            ackEnabled = { true },
            firmware = { HistoryAckFirmware.ALLOWED },
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
        assertEquals(1, driver.activity.ackResult1)
        assertEquals(1, driver.activity.ackEligibleRecords)
        assertEquals(AckKind.RESULT, driver.activity.lastAckKind)
        assertEquals(CommandResult.SUCCESS, driver.activity.lastAckResult)
        assertEquals(PhoneTime(1_700_000_100L, 0), driver.activity.finishedAt)
        assertTrue(link.written.any { commandOf(it) == HistoryAckCommand.NUMBER })
        assertFalse(HistoryAckGate.maySend())
        assertTrue(historyAckReport(driver.activity).contains("GATT status 0"))
        assertTrue(historyAckReport(driver.activity).contains("not a strap result"))
    }

    private fun driver(
        link: HistoryLink,
        ackEnabled: () -> Boolean = { false },
        firmware: () -> String? = { null },
        phone: PhoneClock = PhoneClock { PhoneTime(1_700_000_000L, 0) },
    ): HistorySyncDriver {
        return HistorySyncDriver(
            link = link,
            store = MemorySyncStore(),
            phone = phone,
            schedule = HistoryScheduler { _, _ ->
                HistorySubscription { }
            },
            historyAckEnabled = ackEnabled,
            firmware = firmware,
            nowMs = { 0L },
        )
    }

    private fun sendableToken(): ByteArray = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)

    private fun harness(
        store: MemorySyncStore = MemorySyncStore(),
        chunks: List<ScriptChunk> = listOf(ScriptChunk(sendableToken(), listOf(sampleRecord()))),
        stream: StreamKind = StreamKind.CHUNKS,
        ackResult: Int? = CommandResult.SUCCESS,
        firmware: String? = HistoryAckFirmware.ALLOWED,
    ): OneShotHarness {
        val strap = FakeStrap(
            StrapScript(chunks = chunks, stream = stream, ackResult = ackResult),
        )
        val link = ScriptedHistoryLink(strap)
        var now = 0L
        var enabled = true
        val version = firmware
        val tasks = mutableListOf<() -> Unit>()
        val driver = HistorySyncDriver(
            link = link,
            store = store,
            phone = PhoneClock { PhoneTime(1_700_000_000L, 0) },
            schedule = HistoryScheduler { _, task ->
                tasks += task
                HistorySubscription { }
            },
            historyAckEnabled = { enabled },
            setHistoryAckEnabled = { enabled = it },
            firmware = { version },
            nowMs = { now },
        )
        return OneShotHarness(driver, link, store, tasks) { now = it }
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
