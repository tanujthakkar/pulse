package app.pulse.ble

import java.util.ArrayDeque

/**
 * Runs work one task at a time, in submission order.
 *
 * A task that submits more work does not re-enter: the new work runs after
 * the current task returns. Callers on other threads share that same queue,
 * so a binder callback and a disconnect request cannot overlap.
 */
internal class SerialRunner {
    private val lock = Any()
    private val queue = ArrayDeque<Runnable>()
    private var draining = false
    private var failure: Throwable? = null

    fun execute(task: () -> Unit) {
        synchronized(lock) {
            queue.add(Runnable { task() })
            if (draining) return
            draining = true
        }
        drain()
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                val task = queue.pollFirst()
                if (task == null) draining = false
                task
            } ?: break
            try {
                next.run()
            } catch (error: Throwable) {
                synchronized(lock) {
                    if (failure == null) failure = error
                }
            }
        }
        val thrown = synchronized(lock) { failure.also { failure = null } }
        if (thrown != null) throw thrown
    }
}
