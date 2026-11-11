package app.pulse.ble

import android.content.Context
import android.content.Intent

/**
 * Debug-only switch for the in-process strap.
 *
 * The default is off. Nothing here starts a scan. Release builds use a
 * different source set and do not contain this implementation.
 */
object SimulatedStrap {
    const val EXTRA_ENABLED: String = "simulated_strap"
    const val EXTRA_SCENARIO: String = "simulated_scenario"
    const val PREFS: String = "pulse.simulation"
    const val KEY_ENABLED: String = "enabled"
    const val KEY_SCENARIO: String = "scenario"
    const val ADDRESS: String = "AA:BB:CC:DD:EE:55"
    const val ADVERTISED_NAME: String = "Pulse Sim"

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun scenarioName(context: Context): String =
        prefs(context).getString(KEY_SCENARIO, StrapScenario.NORMAL.wireName) ?: StrapScenario.NORMAL.wireName

    fun scenario(context: Context): StrapScenario = StrapScenario.fromName(scenarioName(context))

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit()
    }

    fun setScenarioName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_SCENARIO, StrapScenario.fromName(name).wireName).commit()
    }

    /**
     * Stores [EXTRA_ENABLED] and [EXTRA_SCENARIO] when they are present.
     *
     * Returns true only when the stored flag or scenario changed, so a second
     * launch with the same extras does not restart again.
     */
    fun applyIntent(context: Context, intent: Intent): Boolean {
        var changed = false
        if (intent.hasExtra(EXTRA_ENABLED)) {
            val want = intent.getBooleanExtra(EXTRA_ENABLED, false)
            if (want != isEnabled(context)) {
                setEnabled(context, want)
                changed = true
            }
        }
        val named = intent.getStringExtra(EXTRA_SCENARIO)
        if (named != null && StrapScenario.fromName(named).wireName != scenarioName(context)) {
            setScenarioName(context, named)
            changed = true
        }
        return changed
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

enum class StrapScenario(val wireName: String) {
    NORMAL("normal"),
    EMPTY("empty"),
    DISCONNECT("disconnect"),
    LOW_BATTERY("low_battery"),
    ;

    companion object {
        fun fromName(name: String?): StrapScenario =
            entries.firstOrNull { it.wireName == name } ?: NORMAL
    }
}
