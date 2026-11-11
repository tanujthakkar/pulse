package app.pulse

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import org.junit.Rule
import org.junit.Test

class GearShots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        renderingMode = RenderingMode.V_SCROLL,
    )

    @Test
    fun gearMenu() {
        paparazzi.snapshot {
            PulseTheme {
                MoreScreen(
                    onConnect = {},
                    onDebug = {},
                    onImport = {},
                    ackEnabled = true,
                    onAckEnabled = {},
                )
            }
        }
    }
}
