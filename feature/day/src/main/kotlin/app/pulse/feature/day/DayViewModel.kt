package app.pulse.feature.day

import androidx.lifecycle.ViewModel
import app.pulse.data.DayMetricsSource
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-day metrics for the latest stored strap.
 *
 * The first read and each refresh run off the main thread. The text is the
 * same text a debug frame export writes above the frame log.
 */
@HiltViewModel
class DayViewModel internal constructor(
    private val source: DayMetricsSource,
    private val executor: Executor,
) : ViewModel() {
    @Inject
    constructor(source: DayMetricsSource) : this(source, newDayExecutor())

    private val generation = AtomicInteger()
    private val _report = MutableStateFlow(READING)
    val report: StateFlow<String> = _report.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val ticket = generation.incrementAndGet()
        _report.value = READING
        executor.execute {
            val text = try {
                source.report()
            } catch (_: Exception) {
                "Day metrics could not be read.\n"
            }
            if (generation.get() == ticket) _report.value = text
        }
    }

    override fun onCleared() {
        (executor as? ExecutorService)?.shutdown()
    }

    companion object {
        const val READING: String = "Reading stored history."
    }
}

private fun newDayExecutor(): ExecutorService {
    return Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pulse-day").apply { isDaemon = true }
    }
}
