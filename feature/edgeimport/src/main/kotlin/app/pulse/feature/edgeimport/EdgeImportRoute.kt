package app.pulse.feature.edgeimport

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun EdgeImportRoute(
    onBack: () -> Unit,
    viewModel: EdgeImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.onDocument(uri)
    }
    EdgeImportScreen(
        state = state,
        onPick = { picker.launch(arrayOf("*/*")) },
        onCommit = viewModel::commit,
        onBack = onBack,
    )
}
