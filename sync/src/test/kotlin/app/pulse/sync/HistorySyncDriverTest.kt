package app.pulse.sync

import app.pulse.testing.fakestrap.FakeStrap
import app.pulse.testing.fakestrap.StrapScript
import app.pulse.wire.codec.ClockCommand
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
        assertEquals(SessionOutcome.DISCONNECTED, driver.activity.outcome)
        assertEquals(PhoneTime(1_700_000_000L, 7), driver.activity.finishedAt)
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

    override fun write(frame: ByteArray): Boolean {
        written += frame.copyOf()
        outbox += frame
        return true
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
