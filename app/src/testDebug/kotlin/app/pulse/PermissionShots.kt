package app.pulse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.pulse.feature.connect.ConnectScreen
import app.pulse.feature.connect.ConnectUi
import app.pulse.feature.connect.LinkPermission
import app.pulse.feature.home.PulsePalette
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import org.junit.Rule
import org.junit.Test

class PermissionPhoneShots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        renderingMode = RenderingMode.NORMAL,
        useDeviceResolution = true,
    )

    @Test
    fun permissionPrompt() = paparazzi.snapshot { prompt() }
}

class PermissionLargeShots {
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
    fun permissionPrompt() = paparazzi.snapshot { prompt() }
}

@androidx.compose.runtime.Composable
private fun prompt() {
    PulseTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(PulsePalette.background),
        ) {
            ConnectScreen(
                ui = ConnectUi.NeedsGrant(
                    listOf(
                        LinkPermission.Scan,
                        LinkPermission.Connect,
                        LinkPermission.Notifications,
                    ),
                ),
                onRequestGrant = {},
                onOpenSettings = {},
                onEnableBluetooth = {},
            )
        }
    }
}
