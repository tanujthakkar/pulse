package app.pulse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Connect, Debug, and Edge import stay reachable from here. */
@Composable
fun MoreScreen(
    onConnect: () -> Unit,
    onDebug: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("More", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = onConnect, modifier = Modifier.testTag("more_connect")) { Text("Connect") }
        Button(onClick = onDebug, modifier = Modifier.testTag("more_debug")) { Text("Debug") }
        Button(onClick = onImport, modifier = Modifier.testTag("more_import")) { Text("Import") }
    }
}
