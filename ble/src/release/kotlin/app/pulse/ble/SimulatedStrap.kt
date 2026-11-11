package app.pulse.ble

import android.content.Context
import android.content.Intent

/**
 * Release stub. The simulated strap is not compiled into this variant, and
 * the launch extra cannot turn anything on.
 */
object SimulatedStrap {
    const val EXTRA_ENABLED: String = "simulated_strap"
    const val EXTRA_SCENARIO: String = "simulated_scenario"

    fun isEnabled(context: Context): Boolean = false

    fun applyIntent(context: Context, intent: Intent): Boolean = false
}
