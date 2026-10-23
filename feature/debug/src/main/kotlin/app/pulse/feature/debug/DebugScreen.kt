package app.pulse.feature.debug

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.pulse.wire.codec.FrameNote

@Composable
fun DebugScreen(
    title: String,
    body: String,
    rows: List<FrameNote>,
    stored: Int,
    paused: Boolean,
    filter: FrameFilter,
    catalog: FrameCatalog,
    exportStatus: String,
    onPause: () -> Unit,
    onExport: () -> Unit,
    onFilter: (FrameFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("debug_frames"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = title, style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = onPause, modifier = Modifier.testTag("debug_pause")) {
                        Text(if (paused) "Resume" else "Pause")
                    }
                    Button(onClick = onExport, modifier = Modifier.testTag("debug_export")) {
                        Text("Export")
                    }
                }
                Text(
                    text = if (paused) "Paused. $stored stored." else "$stored stored.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag("debug_count"),
                )
                if (exportStatus.isNotEmpty()) {
                    Text(
                        text = exportStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .testTag("debug_export_status"),
                    )
                }
                Row(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !filter.unframedOnly && filter.packetType == null,
                        onClick = { onFilter(FrameFilter()) },
                        label = { Text("All") },
                        modifier = Modifier.testTag("debug_filter_all"),
                    )
                    if (catalog.hasUnframed) {
                        FilterChip(
                            selected = filter.unframedOnly,
                            onClick = { onFilter(FrameFilter(unframedOnly = true)) },
                            label = { Text("Unframed") },
                            modifier = Modifier.testTag("debug_filter_unframed"),
                        )
                    }
                    catalog.types.forEach { type ->
                        FilterChip(
                            selected = !filter.unframedOnly && filter.packetType == type,
                            onClick = { onFilter(FrameFilter(packetType = type)) },
                            label = { Text(type.toString()) },
                            modifier = Modifier.testTag("debug_filter_$type"),
                        )
                    }
                }
                if (rows.isEmpty()) {
                    Text(
                        text = "No frames yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .testTag("debug_empty"),
                    )
                }
            }
        }
        items(rows, key = { it.id }) { note ->
            FrameLine(note)
        }
    }
}

@Composable
private fun FrameLine(note: FrameNote) {
    var open by rememberSaveable(note.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(vertical = 4.dp)
            .testTag("debug_frame_${note.id}"),
    ) {
        Text(
            text = frameLine(note),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        if (open) {
            Text(
                text = note.hex,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .testTag("debug_hex_${note.id}"),
            )
        }
    }
}
