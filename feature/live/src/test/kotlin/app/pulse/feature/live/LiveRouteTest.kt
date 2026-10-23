package app.pulse.feature.live

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.pulse.ble.BondBegin
import app.pulse.ble.DeviceBond
import app.pulse.ble.LiveHeartRate
import app.pulse.ble.SensorContact
import app.pulse.link.LinkAlarm
import app.pulse.link.LinkClock
import app.pulse.link.LinkPorts
import app.pulse.link.LiveHeartRateFeed
import app.pulse.link.StrapLink
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiveRouteTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aSampleReplacesThePlaceholderWithBpmAndRr() {
        val feed = LiveHeartRateFeed()
        val viewModel = LiveViewModel(quietLink(), feed)
        compose.setContent { LiveRoute(viewModel) }
        compose.onNodeWithTag("live_bpm").assertTextEquals("—")
        compose.onNodeWithText("Open Connect and start a search.").assertIsDisplayed()

        compose.runOnIdle {
            feed.publish(
                LiveHeartRate(
                    tsMillis = 10L,
                    bpm = 72,
                    rrMs = listOf(812, 790),
                    contact = SensorContact.DETECTED,
                ),
            )
        }
        compose.onNodeWithTag("live_bpm").assertTextEquals("72")
        compose.onNodeWithText("R-R 812 ms, 790 ms").assertIsDisplayed()
        compose.onNodeWithText("Skin contact").assertIsDisplayed()

        compose.runOnIdle {
            feed.publish(
                LiveHeartRate(
                    tsMillis = 11L,
                    bpm = 10,
                    rrMs = listOf(1000),
                    contact = SensorContact.NOT_DETECTED,
                ),
            )
        }
        compose.onNodeWithTag("live_bpm").assertTextEquals("—")
        compose.onNodeWithText("10").assertDoesNotExist()
        compose.onNodeWithText("R-R 1000 ms").assertIsDisplayed()
        compose.onNodeWithText("No skin contact").assertIsDisplayed()
    }

    @Test
    fun twoInRangeSamplesDrawTheSparkline() {
        val feed = LiveHeartRateFeed()
        val viewModel = LiveViewModel(quietLink(), feed)
        compose.setContent { LiveRoute(viewModel) }
        compose.runOnIdle {
            feed.publish(LiveHeartRate(1L, 60, listOf(1000), SensorContact.UNSUPPORTED))
        }
        compose.onNodeWithTag("live_sparkline").assertDoesNotExist()
        compose.onNodeWithTag("live_contact").assertDoesNotExist()
        compose.runOnIdle {
            feed.publish(LiveHeartRate(2L, 62, listOf(980), SensorContact.UNSUPPORTED))
        }
        compose.onNodeWithTag("live_sparkline").assertIsDisplayed()
        compose.onNodeWithText("62").assertIsDisplayed()
    }
}

private fun quietLink(): StrapLink = StrapLink(
    ports = object : LinkPorts {
        override fun startScan() = Unit

        override fun stopScan() = Unit

        override fun bondOf(address: String): DeviceBond = DeviceBond.ABSENT

        override fun beginBond(address: String): BondBegin = BondBegin.NOT_STARTED

        override fun cancelBond() = Unit

        override fun openSession(address: String) = Unit

        override fun closeSession() = Unit

        override fun watchHeartRate(address: String) = Unit
    },
    clock = object : LinkClock {
        override fun after(delayMs: Long, task: () -> Unit): LinkAlarm = LinkAlarm { }
    },
)
