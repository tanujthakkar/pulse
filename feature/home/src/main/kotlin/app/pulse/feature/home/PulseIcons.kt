package app.pulse.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/** Simple marks drawn for Pulse. They are not a device vendor's icons. */
enum class PulseMark {
    CHEVRON,
    CHEVRON_LEFT,
    PLUS,
    MOON,
    RUN,
    HEART,
    LUNGS,
    DROP,
    THERMOMETER,
    STEPS,
    PULSE,
    HOME,
    MENU,
    ARROW_UP,
    ARROW_DOWN,
}

@Composable
fun PulseIcon(
    mark: PulseMark,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.1f, cap = StrokeCap.Round)
        when (mark) {
            PulseMark.CHEVRON -> {
                drawLine(tint, Offset(size.width * 0.35f, size.height * 0.2f), Offset(size.width * 0.7f, size.height * 0.5f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(size.width * 0.7f, size.height * 0.5f), Offset(size.width * 0.35f, size.height * 0.8f), stroke.width, StrokeCap.Round)
            }
            PulseMark.CHEVRON_LEFT -> {
                drawLine(tint, Offset(size.width * 0.65f, size.height * 0.2f), Offset(size.width * 0.3f, size.height * 0.5f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(size.width * 0.3f, size.height * 0.5f), Offset(size.width * 0.65f, size.height * 0.8f), stroke.width, StrokeCap.Round)
            }
            PulseMark.PLUS -> {
                drawLine(tint, Offset(size.width * 0.2f, size.height * 0.5f), Offset(size.width * 0.8f, size.height * 0.5f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(size.width * 0.5f, size.height * 0.2f), Offset(size.width * 0.5f, size.height * 0.8f), stroke.width, StrokeCap.Round)
            }
            PulseMark.MOON -> {
                val path = Path().apply {
                    moveTo(size.width * 0.72f, size.height * 0.16f)
                    cubicTo(
                        size.width * 0.16f,
                        size.height * 0.2f,
                        size.width * 0.16f,
                        size.height * 0.8f,
                        size.width * 0.72f,
                        size.height * 0.84f,
                    )
                    cubicTo(
                        size.width * 0.4f,
                        size.height * 0.7f,
                        size.width * 0.4f,
                        size.height * 0.3f,
                        size.width * 0.72f,
                        size.height * 0.16f,
                    )
                    close()
                }
                drawPath(path, tint)
            }
            PulseMark.RUN -> {
                drawCircle(tint, size.minDimension * 0.12f, Offset(size.width * 0.62f, size.height * 0.22f))
                drawLine(tint, Offset(size.width * 0.55f, size.height * 0.36f), Offset(size.width * 0.42f, size.height * 0.62f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(size.width * 0.48f, size.height * 0.46f), Offset(size.width * 0.72f, size.height * 0.4f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(size.width * 0.48f, size.height * 0.46f), Offset(size.width * 0.28f, size.height * 0.58f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(size.width * 0.42f, size.height * 0.62f), Offset(size.width * 0.58f, size.height * 0.86f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(size.width * 0.42f, size.height * 0.62f), Offset(size.width * 0.22f, size.height * 0.78f), stroke.width, StrokeCap.Round)
            }
            PulseMark.HEART -> {
                val path = Path().apply {
                    moveTo(size.width * 0.5f, size.height * 0.82f)
                    cubicTo(size.width * 0.05f, size.height * 0.5f, size.width * 0.12f, size.height * 0.16f, size.width * 0.32f, size.height * 0.22f)
                    cubicTo(size.width * 0.42f, size.height * 0.26f, size.width * 0.48f, size.height * 0.36f, size.width * 0.5f, size.height * 0.42f)
                    cubicTo(size.width * 0.52f, size.height * 0.36f, size.width * 0.58f, size.height * 0.26f, size.width * 0.68f, size.height * 0.22f)
                    cubicTo(size.width * 0.88f, size.height * 0.16f, size.width * 0.95f, size.height * 0.5f, size.width * 0.5f, size.height * 0.82f)
                    close()
                }
                drawPath(path, tint)
            }
            PulseMark.LUNGS -> {
                drawLine(tint, Offset(size.width * 0.5f, size.height * 0.12f), Offset(size.width * 0.5f, size.height * 0.42f), stroke.width, StrokeCap.Round)
                drawRoundRect(tint, Offset(size.width * 0.08f, size.height * 0.28f), Size(size.width * 0.36f, size.height * 0.58f), androidx.compose.ui.geometry.CornerRadius(size.minDimension * 0.18f))
                drawRoundRect(tint, Offset(size.width * 0.56f, size.height * 0.28f), Size(size.width * 0.36f, size.height * 0.58f), androidx.compose.ui.geometry.CornerRadius(size.minDimension * 0.18f))
            }
            PulseMark.DROP -> {
                val path = Path().apply {
                    moveTo(size.width * 0.5f, size.height * 0.12f)
                    quadraticTo(size.width * 0.92f, size.height * 0.48f, size.width * 0.5f, size.height * 0.88f)
                    quadraticTo(size.width * 0.08f, size.height * 0.48f, size.width * 0.5f, size.height * 0.12f)
                    close()
                }
                drawPath(path, tint)
            }
            PulseMark.THERMOMETER -> {
                drawLine(tint, Offset(size.width * 0.5f, size.height * 0.12f), Offset(size.width * 0.5f, size.height * 0.68f), stroke.width, StrokeCap.Round)
                drawCircle(tint, size.minDimension * 0.16f, Offset(size.width * 0.5f, size.height * 0.78f))
            }
            PulseMark.STEPS -> {
                drawOval(tint, Offset(size.width * 0.22f, size.height * 0.4f), Size(size.width * 0.42f, size.height * 0.46f))
                drawCircle(tint, size.minDimension * 0.11f, Offset(size.width * 0.3f, size.height * 0.28f))
                drawCircle(tint, size.minDimension * 0.09f, Offset(size.width * 0.48f, size.height * 0.22f))
                drawCircle(tint, size.minDimension * 0.08f, Offset(size.width * 0.64f, size.height * 0.3f))
            }
            PulseMark.PULSE -> {
                val path = Path().apply {
                    moveTo(size.width * 0.08f, size.height * 0.55f)
                    lineTo(size.width * 0.32f, size.height * 0.55f)
                    lineTo(size.width * 0.42f, size.height * 0.22f)
                    lineTo(size.width * 0.55f, size.height * 0.78f)
                    lineTo(size.width * 0.68f, size.height * 0.4f)
                    lineTo(size.width * 0.92f, size.height * 0.4f)
                }
                drawPath(path, tint, style = stroke)
            }
            PulseMark.HOME -> {
                val path = Path().apply {
                    moveTo(size.width * 0.5f, size.height * 0.16f)
                    lineTo(size.width * 0.84f, size.height * 0.42f)
                    lineTo(size.width * 0.84f, size.height * 0.84f)
                    lineTo(size.width * 0.16f, size.height * 0.84f)
                    lineTo(size.width * 0.16f, size.height * 0.42f)
                    close()
                }
                drawPath(path, tint, style = stroke)
            }
            PulseMark.MENU -> {
                listOf(0.28f, 0.5f, 0.72f).forEach { y ->
                    drawLine(tint, Offset(size.width * 0.18f, size.height * y), Offset(size.width * 0.82f, size.height * y), stroke.width, StrokeCap.Round)
                }
            }
            PulseMark.ARROW_UP -> {
                drawLine(tint, Offset(size.width * 0.5f, size.height * 0.78f), Offset(size.width * 0.5f, size.height * 0.22f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(size.width * 0.28f, size.height * 0.42f), Offset(size.width * 0.5f, size.height * 0.2f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(size.width * 0.72f, size.height * 0.42f), Offset(size.width * 0.5f, size.height * 0.2f), stroke.width, StrokeCap.Round)
            }
            PulseMark.ARROW_DOWN -> {
                drawLine(tint, Offset(size.width * 0.5f, size.height * 0.22f), Offset(size.width * 0.5f, size.height * 0.78f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(size.width * 0.28f, size.height * 0.58f), Offset(size.width * 0.5f, size.height * 0.8f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(size.width * 0.72f, size.height * 0.58f), Offset(size.width * 0.5f, size.height * 0.8f), stroke.width, StrokeCap.Round)
            }
        }
    }
}
