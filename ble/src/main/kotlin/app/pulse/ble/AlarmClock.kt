package app.pulse.ble

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface Alarm {
    fun cancel()
}

/**
 * Schedules the per-operation deadline. [close] releases the scheduler after
 * the session has released its GATT client.
 */
internal interface AlarmClock {
    fun after(delayMs: Long, task: () -> Unit): Alarm

    fun close() {}
}

/**
 * One daemon thread per session. [close] does not interrupt the caller, so a
 * timeout that closes the session from this thread can shut the scheduler down.
 */
internal class SystemAlarmClock : AlarmClock {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "pulse-gatt").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)

    override fun after(delayMs: Long, task: () -> Unit): Alarm {
        if (closed.get()) return Alarm { }
        return try {
            val future = scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            Alarm { future.cancel(false) }
        } catch (_: RejectedExecutionException) {
            Alarm { }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scheduler.shutdown()
    }
}
