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
    onArmAck: () -> Unit = {},
    onCancelAck: () -> Unit = {},
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
            text = "Chunks this attempt",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = ui.chunks,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("sync_chunks"),
        )
        Text(text = "Received records", style = MaterialTheme.typography.labelLarge)
        Text(
            text = ui.records,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("sync_records"),
        )
        Text(text = "Saved records", style = MaterialTheme.typography.labelLarge)
        Text(
            text = ui.savedRecords,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("sync_saved_records"),
        )
        Text(
            text = "Layout-18 rows stored on this phone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(text = "Saved chunks", style = MaterialTheme.typography.labelLarge)
        Text(
            text = ui.savedChunks,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("sync_saved_chunks"),
        )
        Text(
            text = "Chunk rows stored on this phone.",
            style = MaterialTheme.typography.bodyMedium,
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
        Text(
            text = ui.ackWarning,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("sync_ack_warning"),
        )
        val preview = ui.ackPreview
        if (preview != null) {
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("sync_ack_preview"),
            )
        }
        val ackStatus = ui.ackStatus
        if (ackStatus != null) {
            Text(
                text = ackStatus,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("sync_ack_status"),
            )
        }
        if (ui.canArm) {
            Button(
                onClick = onArmAck,
                modifier = Modifier.testTag("sync_ack_arm"),
            ) {
                Text("Arm one acknowledgement")
            }
        }
        if (ui.canCancel) {
            Button(
                onClick = onCancelAck,
                modifier = Modifier.testTag("sync_ack_cancel"),
            ) {
                Text("Cancel acknowledgement")
            }
        }
    }
}
