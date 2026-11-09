package app.pulse

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.pulse.feature.home.PulsePalette

private val PulseColors = darkColorScheme(
    primary = PulsePalette.green,
    onPrimary = Color(0xFF06210F),
    secondary = PulsePalette.blue,
    onSecondary = Color(0xFF041428),
    background = PulsePalette.background,
    onBackground = PulsePalette.text,
    surface = PulsePalette.surface,
    onSurface = PulsePalette.text,
    surfaceContainer = PulsePalette.surface,
)

@Composable
fun PulseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PulseColors,
        content = content,
    )
}
