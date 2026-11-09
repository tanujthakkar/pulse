package app.pulse.feature.edgeimport

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import app.pulse.data.EdgeImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preview and commit for one Edge backup.
 *
 * The document is copied on this executor, then read from the cache file.
 * [commit] runs the importer on that same executor. A failure leaves Pulse
 * unchanged because the importer's write is one transaction.
 */
@HiltViewModel
class EdgeImportViewModel @Inject constructor(
    private val importer: EdgeImporter,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pulse-edge-import").apply { isDaemon = true }
    }
    private var cached: File? = null

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    fun onDocument(uri: Uri) {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (error: Throwable) {
            _state.value = ImportUiState.Failed(importFailureMessage(error))
            return
        }
        if (input == null) {
            _state.value = ImportUiState.Failed("The selected file could not be read.")
            return
        }
        _state.value = ImportUiState.Reading
        executor.execute {
            try {
                val file = EdgeBackupFiles.writeCache(context, input)
                input.close()
                preview(file)
            } catch (error: Throwable) {
                runCatching { input.close() }
                _state.value = ImportUiState.Failed(importFailureMessage(error))
            }
        }
    }

    /** Previews a file that is already on disk. Tests use this. The document picker uses [onDocument]. */
    fun onBackupFile(file: File) {
        _state.value = ImportUiState.Reading
        executor.execute {
            try {
                preview(file)
            } catch (error: Throwable) {
                _state.value = ImportUiState.Failed(importFailureMessage(error))
            }
        }
    }

    fun commit() {
        val file = cached
        if (file == null || _state.value !is ImportUiState.Ready) return
        _state.value = ImportUiState.Running("Writing the backup.")
        executor.execute {
            try {
                val result = importer.commit(file) { name, written, total ->
                    _state.value = ImportUiState.Running(importProgressText(name, written, total))
                }
                _state.value = ImportUiState.Finished(importSummaryText(result))
                if (cached == file) {
                    file.delete()
                    cached = null
                }
            } catch (error: Throwable) {
                _state.value = ImportUiState.Failed(importFailureMessage(error))
            }
        }
    }

    override fun onCleared() {
        cached?.delete()
        cached = null
        executor.shutdown()
    }

    private fun preview(file: File) {
        val preview = importer.preview(file)
        replaceCache(file)
        _state.value = ImportUiState.Ready(preview)
    }

    private fun replaceCache(file: File) {
        val previous = cached
        cached = file
        if (previous != null && previous != file) previous.delete()
    }
}
