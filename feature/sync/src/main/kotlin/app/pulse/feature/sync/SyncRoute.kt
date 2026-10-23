package app.pulse.feature.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SyncRoute(viewModel: SyncViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsState()
    val running by viewModel.running.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("sync_screen"),
    ) {
        Text(text = viewModel.title, style = MaterialTheme.typography.headlineMedium)
        Text(
            text = status,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(top = 12.dp)
                .testTag("sync_status"),
        )
        Button(
            onClick = viewModel::syncNow,
            enabled = !running,
            modifier = Modifier
                .padding(top = 24.dp)
                .testTag("sync_now"),
        ) {
            Text("Sync now")
        }
    }
}
