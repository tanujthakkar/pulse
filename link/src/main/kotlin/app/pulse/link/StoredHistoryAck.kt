package app.pulse.link

import android.content.Context
import android.content.SharedPreferences
import app.pulse.sync.HistoryAckEnabled
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kill switch for history acknowledgement.
 *
 * Missing means on. 50.43.1.0 accepted a prompt command 23 and advanced its
 * boundary, so the first run acknowledges saved chunks. Turning this off
 * survives process death and does not delete stored rows.
 */
@Singleton
class StoredHistoryAck @Inject constructor(
    @ApplicationContext context: Context,
) : HistoryAckEnabled {
    private val opening = PREFS_EXECUTOR.submit(
        Callable {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        },
    )

    private fun prefs(): SharedPreferences {
        try {
            return opening.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    override fun enabled(): Boolean = prefs().getBoolean(KEY, true)

    override fun setEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY, enabled).apply()
    }

    private companion object {
        val PREFS_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "pulse-ack").apply { isDaemon = true }
        }
        const val PREFS: String = "pulse.history_ack"
        const val KEY: String = "enabled"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class HistoryAckBindings {
    @Binds
    @Singleton
    abstract fun historyAck(stored: StoredHistoryAck): HistoryAckEnabled
}
