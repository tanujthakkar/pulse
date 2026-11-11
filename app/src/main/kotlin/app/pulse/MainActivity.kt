package app.pulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (consumeSimulationIntent(this)) return
        enableEdgeToEdge()
        setContent {
            PulseTheme {
                PulseNavHost(onSimulationChanged = { restartPulse(this@MainActivity) })
            }
        }
    }
}
