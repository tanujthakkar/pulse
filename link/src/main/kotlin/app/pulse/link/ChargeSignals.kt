package app.pulse.link

import app.pulse.wire.codec.ChargeEvent
import app.pulse.wire.codec.DecodeResult
import app.pulse.wire.codec.FrameCodec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Charging flag parsed from event frames.
 *
 * Notifications are copied and decoded on [THREAD], not on the GATT callback.
 * Event 7 sets charging on and event 8 sets it off. Any other frame leaves
 * the flag as it was. [clear] runs on the same thread so it cannot pass an
 * event that was queued first.
 */
@Singleton
class ChargeSignals @Inject constructor() {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD).apply { isDaemon = true }
    }
    private val chargingOut = MutableStateFlow<Boolean?>(null)

    /** Null until an event 7 or 8 has been parsed since the last [clear]. */
    val charging: StateFlow<Boolean?> = chargingOut.asStateFlow()

    fun offer(frame: ByteArray) {
        val copy = frame.copyOf()
        executor.execute {
            val decoded = FrameCodec.decode(copy)
            if (decoded !is DecodeResult.Ok) return@execute
            val on = ChargeEvent.charging(decoded.frame) ?: return@execute
            chargingOut.value = on
        }
    }

    fun clear() {
        executor.execute { chargingOut.value = null }
    }

    internal fun awaitIdle() {
        val latch = CountDownLatch(1)
        executor.execute { latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)
    }

    private companion object {
        const val THREAD: String = "pulse-charge"
    }
}
