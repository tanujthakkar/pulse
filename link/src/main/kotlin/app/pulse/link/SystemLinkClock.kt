package app.pulse.link

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One daemon thread for the pairing-help deadline. The process keeps the
 * scheduler; each [LinkAlarm] cancels only its own task.
 */
internal class SystemLinkClock : LinkClock {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "pulse-link").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)

    override fun after(delayMs: Long, task: () -> Unit): LinkAlarm {
        if (closed.get()) return LinkAlarm { }
        return try {
            val future = scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            LinkAlarm { future.cancel(false) }
        } catch (_: RejectedExecutionException) {
            LinkAlarm { }
        }
    }
}
