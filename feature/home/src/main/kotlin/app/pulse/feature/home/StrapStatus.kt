package app.pulse.feature.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.link.StrapPresence

/** Spoken name for the strap icon. A percent is included only while charging. */
fun strapStatusLabel(presence: StrapPresence): String {
    val link = if (presence.connected) "Strap connected" else "Strap disconnected"
    val charge = if (presence.charging) {
        val percent = presence.batteryPercent?.let { ", $it percent" }.orEmpty()
        "charging$percent"
    } else {
        null
    }
    val sync = if (presence.syncing) {
        if (presence.chunks > 0) "syncing, ${presence.chunks} chunks" else "syncing"
    } else {
        null
    }
    return listOfNotNull(link, charge, sync).joinToString(", ")
}

/**
 * Strap glyph drawn for Pulse.
 *
 * A sensor pod with two band tabs, a status dot, a bolt while charging, and
 * an arc while history is catching up. The chunk count is a stored-chunk
 * count. The strap does not send a total, so the arc is not a percentage.
 */
@Composable
fun StrapStatusButton(
    presence: StrapPresence,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    motion: Boolean = true,
) {
    val label = strapStatusLabel(presence)
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .testTag("strap_status")
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StrapGlyph(presence, motion, Modifier.size(36.dp))
        if (presence.charging && presence.batteryPercent != null) {
            Text(
                "${presence.batteryPercent}%",
                color = PulsePalette.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (presence.syncing && presence.chunks > 0) {
            Text(
                presence.chunks.toString(),
                color = PulsePalette.blue,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun StrapGlyph(
    presence: StrapPresence,
    motion: Boolean,
    modifier: Modifier,
) {
    val angle = spinAngle(motion && presence.syncing)
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        val band = PulsePalette.muted
        val pod = PulsePalette.text
        drawRoundRect(
            color = band,
            topLeft = Offset(0f, height * 0.38f),
            size = Size(width * 0.22f, height * 0.24f),
            cornerRadius = CornerRadius(width * 0.08f, width * 0.08f),
        )
        drawRoundRect(
            color = band,
            topLeft = Offset(width * 0.78f, height * 0.38f),
            size = Size(width * 0.22f, height * 0.24f),
            cornerRadius = CornerRadius(width * 0.08f, width * 0.08f),
        )
        drawRoundRect(
            color = pod,
            topLeft = Offset(width * 0.18f, height * 0.22f),
            size = Size(width * 0.64f, height * 0.56f),
            cornerRadius = CornerRadius(width * 0.16f, width * 0.16f),
        )
        if (presence.charging) {
            val bolt = Path().apply {
                moveTo(width * 0.54f, height * 0.30f)
                lineTo(width * 0.36f, height * 0.54f)
                lineTo(width * 0.48f, height * 0.54f)
                lineTo(width * 0.40f, height * 0.74f)
                lineTo(width * 0.66f, height * 0.46f)
                lineTo(width * 0.52f, height * 0.46f)
                close()
            }
            drawPath(bolt, PulsePalette.yellow)
        }
        drawCircle(
            color = if (presence.connected) PulsePalette.green else PulsePalette.red,
            radius = width * 0.11f,
            center = Offset(width * 0.80f, height * 0.80f),
        )
        if (presence.syncing) {
            val inset = width * 0.04f
            drawArc(
                color = PulsePalette.blue,
                startAngle = angle,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(width - inset * 2f, height - inset * 2f),
                style = Stroke(width = width * 0.07f, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun spinAngle(animate: Boolean): Float {
    if (!animate) return 24f
    val transition = rememberInfiniteTransition(label = "strap-sync")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1100, easing = LinearEasing)),
        label = "strap-sync-angle",
    )
    return angle
}
