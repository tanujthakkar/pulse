package app.pulse.feature.connect

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Which permission explanations have already been shown.
 *
 * The preferences file is opened on [PREFS_EXECUTOR]. Constructing this from
 * the Connect screen's main thread must not call [Context.getSharedPreferences],
 * which reads the disk while StrictMode is watching.
 */
internal class SharedPreferencesPermissionPromptMemory(
    context: Context,
) : PermissionPromptMemory {
    private val appContext = context.applicationContext
    private val opening = PREFS_EXECUTOR.submit(
        Callable {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        },
    )

    private fun prefs(): SharedPreferences {
        try {
            return opening.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    override fun prompted(): Set<LinkPermission> =
        prefs().getStringSet(KEY, emptySet())
            .orEmpty()
            .mapNotNull { key -> LinkPermission.entries.firstOrNull { it.name == key } }
            .toSet()

    override fun markPrompted(permissions: Collection<LinkPermission>) {
        val merged = prompted().map { it.name }.toMutableSet()
        permissions.forEach { merged += it.name }
        val done = PREFS_EXECUTOR.submit {
            prefs().edit().putStringSet(KEY, merged).commit()
        }
        try {
            done.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private companion object {
        val PREFS_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "pulse-connect-prefs").apply { isDaemon = true }
        }
        const val PREFS = "pulse_connect_permissions"
        const val KEY = "prompted"
    }
}
