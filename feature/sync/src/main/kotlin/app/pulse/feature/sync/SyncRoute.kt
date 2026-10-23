package app.pulse.feature.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SyncRoute(viewModel: SyncViewModel = hiltViewModel()) {
    val activity by viewModel.activity.collectAsState()
    SyncScreen(
        ui = syncUi(activity),
        onSyncNow = viewModel::syncNow,
        title = viewModel.title,
    )
}
