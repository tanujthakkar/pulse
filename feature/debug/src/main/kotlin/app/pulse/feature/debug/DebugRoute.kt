package app.pulse.feature.debug

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun DebugRoute(viewModel: DebugViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) viewModel.export { openLocal(context, uri) }
    }
    val body by viewModel.body.collectAsState()
    val rows by viewModel.rows.collectAsState()
    val stored by viewModel.stored.collectAsState()
    val paused by viewModel.paused.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val catalog by viewModel.catalog.collectAsState()
    val exportStatus by viewModel.exportStatus.collectAsState()
    DebugScreen(
        title = viewModel.title,
        body = body,
        rows = rows,
        stored = stored,
        paused = paused,
        filter = filter,
        catalog = catalog,
        exportStatus = exportStatus,
        onPause = { viewModel.setPaused(!paused) },
        onExport = { exportLauncher.launch("pulse-frames.txt") },
        onFilter = viewModel::setFilter,
    )
}

/** Storage Access Framework stream for a file the user picked on this phone. */
internal fun openLocal(context: Context, uri: Uri) = context.contentResolver.openOutputStream(uri)
