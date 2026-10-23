package app.pulse.link

import app.pulse.ble.LiveHeartRate
import app.pulse.ble.SensorContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
        feed.clear()
        assertNull(feed.latest.value)
        job.cancel()
    }

    private fun sample(bpm: Int, ts: Long) = LiveHeartRate(
        tsMillis = ts,
        bpm = bpm,
        rrMs = listOf(1000),
        contact = SensorContact.DETECTED,
    )
}
