package app.pulse

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import app.pulse.ble.SimulatedStrap
import app.pulse.feature.home.PulsePalette
import app.pulse.feature.home.PulseSpace
import app.pulse.link.StrapLink
import kotlin.system.exitProcess

/**
 * Applies the debug launch extras, then restarts so the process opens the
 * matching database. The restarted activity does not carry the extras.
 */
fun consumeSimulationIntent(activity: ComponentActivity): Boolean {
    if (!SimulatedStrap.applyIntent(activity, activity.intent)) return false
    restartPulse(activity)
    return true
}

fun restartPulse(activity: Activity) {
    val intent = Intent(activity, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    activity.startActivity(intent)
    activity.finishAffinity()
    exitProcess(0)
}

fun autostartSimulatedStrap(context: Context, link: StrapLink) {
    if (SimulatedStrap.isEnabled(context)) link.start()
}

@Composable
fun SimulatedStrapControls(onRestart: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SimulatedStrap.isEnabled(context)) }
    var scenario by remember { mutableStateOf(SimulatedStrap.scenarioName(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(PulseSpace.sm)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Simulated strap",
                color = PulsePalette.text,
                modifier = Modifier.testTag("sim_strap_label"),
            )
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    SimulatedStrap.setEnabled(context, on)
                    enabled = on
                    onRestart()
                },
                modifier = Modifier.testTag("sim_strap_switch"),
            )
        }
        Text(
            "Debug only. History is stored in a separate database.",
            color = PulsePalette.muted,
            modifier = Modifier.testTag("sim_strap_note"),
        )
        listOf(
            "normal" to "Normal",
            "empty" to "Empty",
            "disconnect" to "Disconnect",
            "low_battery" to "Low battery",
            "charging" to "Charging",
        ).forEach { (name, label) ->
            TextButton(
                onClick = {
                    if (name == scenario) return@TextButton
                    SimulatedStrap.setScenarioName(context, name)
                    scenario = name
                    onRestart()
                },
                modifier = Modifier.testTag("sim_scenario_$name"),
            ) {
                Text(
                    label,
                    color = if (name == scenario) PulsePalette.green else PulsePalette.text,
                )
            }
        }
    }
}
