package app.pulse.link

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
 * null after the session closes. Milestone 1 does not write these anywhere.
 */
class LiveHeartRateFeed {
    private val latestOut = MutableStateFlow<LiveHeartRate?>(null)
    val latest: StateFlow<LiveHeartRate?> = latestOut.asStateFlow()

    private val samplesOut = MutableSharedFlow<LiveHeartRate>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val samples: Flow<LiveHeartRate> = samplesOut.asSharedFlow()

    fun publish(sample: LiveHeartRate) {
        latestOut.value = sample
        samplesOut.tryEmit(sample)
    }

    fun clear() {
        latestOut.value = null
    }
}
