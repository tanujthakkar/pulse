package app.pulse.feature.edgeimport

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Copies a user-selected backup into app cache.
 *
 * The document stream is read once. The cache file is then marked read-only.
 * The caller's document is not opened for write.
 */
object EdgeBackupFiles {
    fun writeCache(context: Context, input: InputStream): File {
        val dir = File(context.cacheDir, "edge-import").apply { mkdirs() }
        val dest = File(dir, "${UUID.randomUUID()}.db")
        dest.outputStream().use { output -> input.copyTo(output) }
        dest.setReadOnly()
        return dest
    }
}
