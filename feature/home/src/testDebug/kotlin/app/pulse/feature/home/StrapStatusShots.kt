package app.pulse.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.pulse.link.StrapPresence
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import org.junit.Rule
import org.junit.Test

/** Header states for the strap icon. The arc is drawn still so the frame is stable. */
class StrapStatusShots {
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
    fun connected() = snap(StrapPresence(connected = true))

    @Test
    fun disconnected() = snap(StrapPresence(connected = false))

    @Test
    fun charging() = snap(StrapPresence(connected = true, charging = true, batteryPercent = 64))

    @Test
    fun syncing() = snap(StrapPresence(connected = true, syncing = true, chunks = 12))

    private fun snap(presence: StrapPresence) = snapPulse(paparazzi) {
        Box(
            Modifier
                .fillMaxSize()
                .background(PulsePalette.background)
                .padding(PulseSpace.md),
        ) {
            Box(Modifier.fillMaxWidth()) {
                DateHeader(
                    label = "Saturday, May 16",
                    previousEnabled = true,
                    nextEnabled = false,
                    onPrevious = {},
                    onNext = {},
                    onOpenCalendar = {},
                )
                StrapStatusButton(
                    presence = presence,
                    onClick = {},
                    modifier = Modifier.align(Alignment.TopEnd),
                    motion = false,
                )
            }
        }
    }
}
