package app.pulse.metrics

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class MetricsTest {
    @Test
    fun noMetricIsImplementedInTheSkeleton() {
        assertFalse(Metrics.IMPLEMENTED)
    }
}
