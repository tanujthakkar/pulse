package app.pulse.feature.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun LiveRoute(viewModel: LiveViewModel = hiltViewModel()) {
    val phase by viewModel.phase.collectAsState()
    val latest by viewModel.latest.collectAsState()
    var trace by remember { mutableStateOf<List<Int>>(emptyList()) }
    LaunchedEffect(latest) {
        val sample = latest
        trace = if (sample == null) {
            emptyList()
        } else {
            appendTrace(trace, displayedBpm(sample.bpm))
        }
    }
    LiveScreen(ui = liveUi(phase, latest, trace))
}
