package app.pulse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.pulse.feature.home.PulsePalette
import app.pulse.feature.sync.SyncViewModel

/** Connect, Debug, and Edge import stay reachable from here. */
@Composable
fun MoreRoute(
    onConnect: () -> Unit,
    onDebug: () -> Unit,
    onImport: () -> Unit,
    onSimulationChanged: () -> Unit = {},
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val ackEnabled by viewModel.historyAckEnabled.collectAsState(viewModel.currentHistoryAck())
    MoreScreen(
        onConnect = onConnect,
        onDebug = onDebug,
        onImport = onImport,
        ackEnabled = ackEnabled,
        onAckEnabled = viewModel::setHistoryAckEnabled,
        onSimulationChanged = onSimulationChanged,
    )
}

@Composable
fun MoreScreen(
    onConnect: () -> Unit,
    onDebug: () -> Unit,
    onImport: () -> Unit,
    ackEnabled: Boolean,
    onAckEnabled: (Boolean) -> Unit,
    onSimulationChanged: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(PulsePalette.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "More",
            color = PulsePalette.text,
            style = MaterialTheme.typography.headlineMedium,
        )
        Button(onClick = onConnect, modifier = Modifier.testTag("more_connect")) { Text("Connect") }
        Button(onClick = onDebug, modifier = Modifier.testTag("more_debug")) { Text("Debug") }
        Button(onClick = onImport, modifier = Modifier.testTag("more_import")) { Text("Import") }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Acknowledge saved history",
                color = PulsePalette.text,
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                checked = ackEnabled,
                onCheckedChange = onAckEnabled,
                modifier = Modifier.testTag("sync_ack_switch"),
            )
        }
        Text(
            "Command 23 is sent only after a chunk is saved, and only while this is on.",
            color = PulsePalette.muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("more_ack_note"),
        )
        SimulatedStrapControls(onRestart = onSimulationChanged)
    }
}
