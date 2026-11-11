package app.pulse.data

import android.content.Context
import android.content.SharedPreferences
import app.pulse.metrics.BodyProfile
import app.pulse.metrics.Sex
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Age, sex, height, and weight for the published VO2 and calorie estimates.
 *
 * The history database does not store this. A missing field leaves those
 * cards blank. Writes use apply, so the click that saves does not wait on disk.
 */
@Singleton
class StoredBodyProfile @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val opening = PREFS_EXECUTOR.submit(
        Callable {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        },
    )
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private fun prefs(): SharedPreferences {
        try {
            return opening.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    fun current(): BodyProfile? {
        val stored = prefs()
        val sex = stored.getString(SEX, null)?.let { runCatching { Sex.valueOf(it) }.getOrNull() } ?: return null
        if (!stored.contains(AGE) || !stored.contains(HEIGHT) || !stored.contains(WEIGHT)) return null
        val profile = BodyProfile(
            sex = sex,
            ageYears = stored.getInt(AGE, 0),
            heightCm = stored.getInt(HEIGHT, 0),
            weightKg = stored.getFloat(WEIGHT, 0f).toDouble(),
        )
        return profile.takeIf { it.valid() }
    }

    fun save(profile: BodyProfile) {
        if (!profile.valid()) return
        prefs().edit()
            .putString(SEX, profile.sex.name)
            .putInt(AGE, profile.ageYears)
            .putInt(HEIGHT, profile.heightCm)
            .putFloat(WEIGHT, profile.weightKg.toFloat())
            .apply()
        listeners.forEach { it() }
    }

    fun watch(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private companion object {
        val PREFS_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "pulse-profile").apply { isDaemon = true }
        }
        const val PREFS: String = "pulse.profile"
        const val SEX: String = "sex"
        const val AGE: String = "age"
        const val HEIGHT: String = "height"
        const val WEIGHT: String = "weight"
    }
}
