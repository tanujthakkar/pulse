package app.pulse.data

import android.content.Context

/**
 * Debug database file.
 *
 * The preference name and key match the debug strap switch. Simulated rows
 * go in [SIMULATED] and never in [REAL].
 */
object PulseStoreFiles {
    const val REAL: String = "pulse.db"
    const val SIMULATED: String = "pulse-sim.db"

    fun name(context: Context): String {
        val enabled = context.applicationContext
            .getSharedPreferences("pulse.simulation", Context.MODE_PRIVATE)
            .getBoolean("enabled", false)
        return if (enabled) SIMULATED else REAL
    }
}
