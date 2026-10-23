package app.pulse.sync

import app.pulse.wire.codec.ClockCommand
import app.pulse.wire.codec.DecodeResult
import app.pulse.wire.codec.FrameCodec
import app.pulse.wire.codec.HistoryAckGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        assertFalse(HistoryAckGate.maySend())
    }

    private fun driver(link: FakeHistoryLink): HistorySyncDriver {
        return HistorySyncDriver(
            link = link,
            store = MemorySyncStore(),
            phone = PhoneClock { PhoneTime(1_700_000_000L, 0) },
            schedule = HistoryScheduler { _, _ ->
                HistorySubscription { }
            },
            nowMs = { 0L },
        )
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
