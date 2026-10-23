package app.pulse.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SerialRunnerTest {
    @Test
    fun nestedWorkRunsAfterTheCurrentTask() {
        val runner = SerialRunner()
        var depth = 0
        var maxDepth = 0
        val order = mutableListOf<String>()
        runner.execute {
            depth += 1
            maxDepth = maxOf(maxDepth, depth)
            order += "outer-start"
            runner.execute {
                depth += 1
                maxDepth = maxOf(maxDepth, depth)
                order += "inner"
                depth -= 1
            }
            order += "outer-end"
            depth -= 1
        }
        assertEquals(1, maxDepth)
        assertEquals(0, depth)
        assertEquals(listOf("outer-start", "outer-end", "inner"), order)
    }

    @Test
    fun callersFromSeveralThreadsDoNotOverlap() {
        val runner = SerialRunner()
        val depth = AtomicInteger(0)
        val maxDepth = AtomicInteger(0)
        val ready = CountDownLatch(8)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) {
            pool.submit {
                ready.countDown()
                go.await()
                runner.execute {
                    val now = depth.incrementAndGet()
                    maxDepth.updateAndGet { current -> maxOf(current, now) }
                    Thread.sleep(15)
                    depth.decrementAndGet()
                }
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1, maxDepth.get())
        assertEquals(0, depth.get())
    }
}
