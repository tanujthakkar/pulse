package app.pulse.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.pulse.link.StrapPresence
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import org.junit.Rule
import org.junit.Test

class HomePhoneShots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        renderingMode = RenderingMode.NORMAL,
        useDeviceResolution = true,
    )

    @Test
    fun homeTop() = snap { phoneTop(shotDay()) }

    @Test
    fun myDay() = snap { phoneDay(shotDay()) }

    @Test
    fun myDashboard() = snap { phoneDashboard(shotDay()) }

    @Test
    fun healthMonitorWithData() = snap { HealthMonitorScreen(shotDay().monitor, onBack = {}) }

    @Test
    fun healthMonitorEmpty() = snap { HealthMonitorScreen(shotEmpty().monitor, onBack = {}) }

    private fun snap(content: @androidx.compose.runtime.Composable () -> Unit) = snapPulse(paparazzi, content)
}

class HomeLargeShots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1440,
            screenHeight = 3120,
            xdpi = 500,
            ydpi = 500,
        ),
        renderingMode = RenderingMode.NORMAL,
        useDeviceResolution = true,
    )

    @Test
    fun homeTop() = snap { phoneTop(shotDay()) }

    @Test
    fun myDay() = snap { phoneDay(shotDay()) }

    @Test
    fun myDashboard() = snap { phoneDashboard(shotDay()) }

    @Test
    fun healthMonitorWithData() = snap { HealthMonitorScreen(shotDay().monitor, onBack = {}) }

    @Test
    fun healthMonitorEmpty() = snap { HealthMonitorScreen(shotEmpty().monitor, onBack = {}) }

    private fun snap(content: @androidx.compose.runtime.Composable () -> Unit) = snapPulse(paparazzi, content)
}

@androidx.compose.runtime.Composable
private fun phoneTop(content: HomeContent) {
    Box(Modifier.fillMaxSize().background(PulsePalette.background)) {
        Column(Modifier.padding(PulseSpace.md)) {
            Box(Modifier.fillMaxWidth()) {
                DateHeader(
                    label = content.headerLabel,
                    previousEnabled = true,
                    nextEnabled = !content.isToday,
                    onPrevious = {},
                    onNext = {},
                    onOpenCalendar = {},
                )
                StrapStatusButton(
                    presence = StrapPresence(connected = true),
                    onClick = {},
                    modifier = Modifier.align(Alignment.CenterEnd),
                    motion = false,
                )
            }
            SummaryRings(content, onOpenActivity = {})
            HealthMonitorButton(onClick = {})
        }
        PulseBottomBar(
            selected = HomeTab.HOME,
            onHome = {},
            onMore = {},
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@androidx.compose.runtime.Composable
private fun phoneDay(content: HomeContent) {
    Box(Modifier.fillMaxSize().background(PulsePalette.background)) {
        MyDayBlock(
            content = content,
            onAdd = {},
            onOpen = {},
            onEdit = {},
            onDelete = {},
            modifier = Modifier.padding(PulseSpace.md),
        )
    }
}

@androidx.compose.runtime.Composable
private fun phoneDashboard(content: HomeContent) {
    Box(Modifier.fillMaxSize().background(PulsePalette.background)) {
        DashboardBlock(content, Modifier.padding(PulseSpace.md))
    }
}
