package app.pulse.feature.day

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.pulse.data.DayMetricsSource
import java.time.ZoneId
import java.util.concurrent.Executor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w480dp-h800dp")
class DayRouteTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theScreenShowsTheReportAndRefreshReplacesIt() {
        val source = MutableReport("HR coverage 4 / 4 stored seconds (100.0%). R-R in 300-2000 ms on 1.")
        val viewModel = DayViewModel(source, Executor { it.run() })
        compose.setContent { DayRoute(viewModel) }
        compose.onNodeWithTag("day_report").assertTextContains("HR coverage 4 / 4", substring = true)
        compose.onNodeWithTag("day_report").assertTextContains("R-R in 300-2000 ms", substring = true)
        source.text = "No layout-18 seconds are stored."
        compose.onNodeWithTag("day_refresh").performClick()
        compose.onNodeWithTag("day_report").assertTextContains("No layout-18 seconds are stored.", substring = true)
    }

    private class MutableReport(var text: String) : DayMetricsSource {
        override fun report(zone: ZoneId): String = text
    }
}
