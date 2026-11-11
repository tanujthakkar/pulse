package app.pulse

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import app.pulse.link.StrapLink

/** Release builds have no simulated strap. The launch extra is ignored. */
fun consumeSimulationIntent(activity: ComponentActivity): Boolean = false

fun restartPulse(activity: Activity) = Unit

fun autostartSimulatedStrap(context: Context, link: StrapLink) = Unit

@Composable
fun SimulatedStrapControls(onRestart: () -> Unit) = Unit
