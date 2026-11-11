package app.pulse.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class HomeTab { HOME, MORE }

@Composable
fun PulseBottomBar(
    selected: HomeTab,
    onHome: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(PulsePalette.background)
            .padding(horizontal = PulseSpace.lg, vertical = PulseSpace.sm),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabButton("Home", PulseMark.HOME, selected == HomeTab.HOME, "nav_home", onHome)
        TabButton("More", PulseMark.MENU, selected == HomeTab.MORE, "nav_more", onMore)
    }
}

@Composable
private fun TabButton(
    label: String,
    mark: PulseMark,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val tint = if (selected) PulsePalette.text else PulsePalette.muted
    Column(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) PulsePalette.surface else PulsePalette.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 8.dp)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PulseIcon(mark, tint, Modifier.size(22.dp))
        Text(label, color = tint, fontSize = PulseType.label, fontWeight = FontWeight.Medium)
    }
}
