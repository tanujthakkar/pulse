package app.pulse.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

@Composable
fun SyncScreen(
    ui: SyncUi,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Sync",
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("sync_screen"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        Text(text = "State", style = MaterialTheme.typography.labelLarge)
        Text(
            text = ui.state,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("sync_state"),
        )
        if (!ui.canSync) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(24.dp)
                    .testTag("sync_busy"),
                strokeWidth = 2.dp,
            )
        }
        Text(
            text = "Chunks",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = ui.chunks,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("sync_chunks"),
        )
        Text(text = "Records", style = MaterialTheme.typography.labelLarge)
        Text(
            text = ui.records,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("sync_records"),
        )
        Text(
            text = "Last outcome",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = ui.outcome,
            style = MaterialTheme.typography.bodyLarge,
            color = if (ui.outcomeIsError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.testTag(if (ui.outcomeIsError) "sync_error" else "sync_outcome"),
        )
        val notice = ui.notice
        if (notice != null) {
            Text(
                text = notice,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("sync_notice"),
            )
        }
        Text(text = "Time", style = MaterialTheme.typography.labelLarge)
        Text(
            text = ui.time,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("sync_time"),
        )
        Button(
            onClick = onSyncNow,
            enabled = ui.canSync,
            modifier = Modifier
                .padding(top = 16.dp)
                .testTag("sync_now"),
        ) {
            Text("Sync now")
        }
    }
}
