package app.pulse.link

import app.pulse.ble.HeartRateChannel
import app.pulse.ble.LiveHeartRate
import app.pulse.ble.SensorContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveHeartRateFeedTest {
    @Test
    fun publishReplacesLatestAndClearDropsIt() = runBlocking {
        val feed = LiveHeartRateFeed()
        val seen = mutableListOf<LiveHeartRate>()
        val job = launch(Dispatchers.Unconfined) { feed.samples.collect { seen += it } }
        val first = sample(bpm = 60, ts = 1L)
        val second = sample(bpm = 61, ts = 2L)
        feed.publish(first)
        feed.publish(second)
        assertEquals(second, feed.latest.value)
        assertEquals(listOf(first, second), seen)
        assertEquals(listOf(first, second), feed.recent())
        feed.applyChannel(HeartRateChannel(subscribed = true, notifications = 4))
        assertEquals(HeartRateChannel(subscribed = true, notifications = 4), feed.channel.value)
        feed.clear()
        assertNull(feed.latest.value)
        assertEquals(HeartRateChannel(), feed.channel.value)
        assertTrue(feed.recent().isEmpty())
        job.cancel()
    }

    @Test
    fun recentDropsSamplesOlderThanThreeMinutes() {
        val jumped = LiveHeartRateFeed()
        jumped.publish(sample(bpm = 50, ts = 0L))
        jumped.publish(sample(bpm = 70, ts = 200_000L))
        assertEquals(listOf(70), jumped.recent().map { it.bpm })
        val feed = LiveHeartRateFeed()
        repeat(200) { index -> feed.publish(sample(bpm = index, ts = 1_000L * index)) }
        val kept = feed.recent()
        assertEquals(181, kept.size)
        assertEquals(19, kept.first().bpm)
        assertEquals(199, kept.last().bpm)
    }

    private fun sample(bpm: Int, ts: Long) = LiveHeartRate(
        tsMillis = ts,
        bpm = bpm,
        rrMs = listOf(1000),
        contact = SensorContact.DETECTED,
    )
}
