package app.pulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.pulse.link.ConnectedLinkHost
import app.pulse.link.StrapLink
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var strapLink: StrapLink
    @Inject lateinit var connectedLink: ConnectedLinkHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (consumeSimulationIntent(this)) return
        enableEdgeToEdge()
        setContent {
            PulseTheme {
                StartupPermission(
                    onGranted = {
                        autostartSimulatedStrap(this@MainActivity, strapLink)
                        connectedLink.onPermissionsChanged()
                    },
                ) {
                    PulseNavHost(onSimulationChanged = { restartPulse(this@MainActivity) })
                }
            }
        }
    }
}
