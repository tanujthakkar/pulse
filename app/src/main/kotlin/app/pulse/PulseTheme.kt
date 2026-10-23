package app.pulse

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PulseColors = lightColorScheme(
    primary = Color(0xFF0F6E6E),
    onPrimary = Color(0xFFFFFCF8),
    secondary = Color(0xFFB6403A),
    onSecondary = Color(0xFFFFFCF8),
    background = Color(0xFFF6F4EF),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFFFFCF8),
    onSurface = Color(0xFF1C1B19),
)

@Composable
fun PulseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PulseColors,
        content = content,
    )
}
