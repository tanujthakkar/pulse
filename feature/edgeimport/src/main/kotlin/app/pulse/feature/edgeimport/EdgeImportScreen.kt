package app.pulse.feature.edgeimport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.pulse.data.EdgeBackupException
import app.pulse.data.EdgePreview

sealed interface ImportUiState {
    data object Idle : ImportUiState

    data object Reading : ImportUiState

    data class Ready(val preview: EdgePreview) : ImportUiState

    data class Running(val progress: String) : ImportUiState

    data class Finished(val summary: String) : ImportUiState

    data class Failed(val message: String) : ImportUiState
}

fun importFailureMessage(error: Throwable): String = when (error) {
    is EdgeBackupException -> error.message ?: "The backup could not be imported."
    else -> "The backup could not be imported."
}

@Composable
fun EdgeImportScreen(
    state: ImportUiState,
    onPick: () -> Unit,
    onCommit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state is ImportUiState.Reading || state is ImportUiState.Running
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("import_screen"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "Import", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Bring an Edge SQLite backup into Pulse. The file is copied into cache and opened read-only.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onPick,
                enabled = !busy,
                modifier = Modifier.testTag("import_pick"),
            ) {
                Text("Choose backup")
            }
            Button(
                onClick = onBack,
                enabled = !busy,
                modifier = Modifier.testTag("import_back"),
            ) {
                Text("Back")
            }
        }
        if (state is ImportUiState.Ready) {
            Button(
                onClick = onCommit,
                modifier = Modifier.testTag("import_commit"),
            ) {
                Text("Import")
            }
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(24.dp)
                    .testTag("import_busy"),
                strokeWidth = 2.dp,
            )
        }
        Text(
            text = bodyFor(state),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("import_body"),
        )
    }
}

private fun bodyFor(state: ImportUiState): String = when (state) {
    ImportUiState.Idle -> "Choose a backup to see counts and the date range before anything is written."
    ImportUiState.Reading -> "Reading the backup."
    is ImportUiState.Ready -> importPreviewText(state.preview)
    is ImportUiState.Running -> state.progress
    is ImportUiState.Finished -> state.summary
    is ImportUiState.Failed -> state.message
}
