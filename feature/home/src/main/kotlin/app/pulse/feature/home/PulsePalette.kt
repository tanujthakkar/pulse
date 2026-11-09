package app.pulse.feature.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pulse dark surfaces and the dial colors.
 *
 * Recovery green, yellow, and red are display bands for the 0–100 formula.
 * Strain is blue. Sleep is purple. None of these are a brand asset.
 */
object PulsePalette {
    val background: Color = Color(0xFF0E0F12)
    val surface: Color = Color(0xFF1A1C21)
    val track: Color = Color(0xFF2A2D34)
    val text: Color = Color(0xFFF4F5F7)
    val muted: Color = Color(0xFF9AA0A8)
    val green: Color = Color(0xFF3DDC84)
    val yellow: Color = Color(0xFFF5C451)
    val red: Color = Color(0xFFF05D5E)
    val blue: Color = Color(0xFF3D8BFF)
    val purple: Color = Color(0xFFA78BFA)
}

object PulseSpace {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
}

object PulseType {
    val dial = 28.sp
    val title = 20.sp
    val body = 14.sp
    val label = 12.sp
}

enum class DialTone { GREEN, YELLOW, RED, BLUE, PURPLE, MUTED }

fun DialTone.color(): Color = when (this) {
    DialTone.GREEN -> PulsePalette.green
    DialTone.YELLOW -> PulsePalette.yellow
    DialTone.RED -> PulsePalette.red
    DialTone.BLUE -> PulsePalette.blue
    DialTone.PURPLE -> PulsePalette.purple
    DialTone.MUTED -> PulsePalette.muted
}
