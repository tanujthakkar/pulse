package app.pulse.link

import app.pulse.ble.HeartRateChannel
import app.pulse.ble.LiveHeartRate
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory heart-rate samples from the open GATT session.
 *
 * [samples] is every parsed notification. [latest] is the newest one, or
 * null after the session closes. [recent] keeps the last three minutes, up
 * to [RECENT_CAP] points, for the health-monitor line. Nothing here is
 * written to disk, and nothing here enables a strap notification.
 */
class LiveHeartRateFeed {
    private val latestOut = MutableStateFlow<LiveHeartRate?>(null)
    val latest: StateFlow<LiveHeartRate?> = latestOut.asStateFlow()

    private val channelOut = MutableStateFlow(HeartRateChannel())

    /** Subscribe state copied from the open session. Not a command. */
    val channel: StateFlow<HeartRateChannel> = channelOut.asStateFlow()

    private val samplesOut = MutableSharedFlow<LiveHeartRate>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val samples: Flow<LiveHeartRate> = samplesOut.asSharedFlow()

    private val recentSamples = ArrayDeque<LiveHeartRate>()

    fun publish(sample: LiveHeartRate) {
        latestOut.value = sample
        synchronized(recentSamples) {
            recentSamples.addLast(sample)
            val cutoff = sample.tsMillis - RECENT_WINDOW_MS
            while (recentSamples.size > 1 && recentSamples.first().tsMillis < cutoff) {
                recentSamples.removeFirst()
            }
            while (recentSamples.size > RECENT_CAP) recentSamples.removeFirst()
        }
        samplesOut.tryEmit(sample)
    }

    /** Newest samples, oldest first. Cleared with [clear]. */
    fun recent(): List<LiveHeartRate> = synchronized(recentSamples) { recentSamples.toList() }

    fun applyChannel(channel: HeartRateChannel) {
        channelOut.value = channel
    }

    fun clear() {
        latestOut.value = null
        synchronized(recentSamples) { recentSamples.clear() }
        channelOut.value = HeartRateChannel()
    }

    private companion object {
        const val RECENT_WINDOW_MS: Long = 180_000L
        const val RECENT_CAP: Int = 600
    }
}
