package app.pulse.data

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The one thread that opens the database and runs directory and observer work.
 *
 * GATT callbacks and application startup both arrive on the main thread.
 * Room rejects that thread, and a preference load there is a disk read.
 * Callers wait for the result, so a save is visible before the next step.
 */
internal object DbWork {
    private val onDb = ThreadLocal<Boolean>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pulse-db").apply { isDaemon = true }
    }

    fun <T> call(block: () -> T): T {
        if (onDb.get() == true) return block()
        try {
            return executor.submit(
                Callable {
                    onDb.set(true)
                    try {
                        block()
                    } finally {
                        onDb.remove()
                    }
                },
            ).get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("database work interrupted", error)
        }
    }
}
