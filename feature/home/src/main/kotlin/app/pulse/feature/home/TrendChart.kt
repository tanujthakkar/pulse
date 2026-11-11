package app.pulse.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

enum class ChartStyle { BARS, LINE, STACKED, FLOATING, DUAL_LINE }

data class ChartMark(
    val label: String,
    val primary: Float?,
    val secondary: Float? = null,
    val highlighted: Boolean = false,
)

@Composable
fun TrendChart(
    points: List<ChartMark>,
    style: ChartStyle,
    yMin: Float,
    yMax: Float,
    modifier: Modifier = Modifier,
    bandLow: Float? = null,
    bandHigh: Float? = null,
    yLabel: (Float) -> String = { it.roundToInt().toString() },
    tag: String = "trend_chart",
    onClick: (() -> Unit)? = null,
) {
    val span = (yMax - yMin).takeIf { it > 0f } ?: 1f
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .testTag(tag),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Column(
                Modifier.width(36.dp).height(160.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            ) {
                Text(yLabel(yMax), color = PulsePalette.muted, fontSize = 10.sp)
                Text(yLabel((yMin + yMax) / 2f), color = PulsePalette.muted, fontSize = 10.sp)
                Text(yLabel(yMin), color = PulsePalette.muted, fontSize = 10.sp)
            }
        Canvas(
            Modifier
                .weight(1f)
                .height(160.dp),
        ) {
            val low = bandLow
            val high = bandHigh
            if (low != null && high != null && high > low) {
                val top = size.height * (1f - (high - yMin) / span)
                val bottom = size.height * (1f - (low - yMin) / span)
                drawRect(
                    color = PulsePalette.blue.copy(alpha = 0.16f),
                    topLeft = Offset(0f, top.coerceIn(0f, size.height)),
                    size = androidx.compose.ui.geometry.Size(
                        size.width,
                        (bottom - top).coerceAtLeast(0f),
                    ),
                )
            }
            val step = if (points.isEmpty()) size.width else size.width / points.size
            points.forEachIndexed { index, mark ->
                val left = index * step
                val center = left + step / 2f
                if (mark.highlighted) {
                    drawRect(
                        color = Color.White.copy(alpha = 0.06f),
                        topLeft = Offset(left, 0f),
                        size = androidx.compose.ui.geometry.Size(step, size.height),
                    )
                }
                fun yOf(value: Float): Float = size.height * (1f - (value - yMin) / span)
                when (style) {
                    ChartStyle.BARS -> {
                        val value = mark.primary ?: return@forEachIndexed
                        val top = yOf(value)
                        drawRect(
                            color = if (mark.highlighted) PulsePalette.blue else PulsePalette.blue.copy(alpha = 0.72f),
                            topLeft = Offset(left + step * 0.22f, top),
                            size = androidx.compose.ui.geometry.Size(step * 0.56f, (size.height - top).coerceAtLeast(0f)),
                        )
                    }
                    ChartStyle.STACKED -> {
                        val deep = mark.primary ?: return@forEachIndexed
                        val rem = mark.secondary ?: 0f
                        val totalTop = yOf(deep + rem)
                        val split = yOf(rem)
                        drawRect(
                            color = PulsePalette.deep,
                            topLeft = Offset(left + step * 0.22f, totalTop),
                            size = androidx.compose.ui.geometry.Size(step * 0.56f, (split - totalTop).coerceAtLeast(0f)),
                        )
                        drawRect(
                            color = PulsePalette.rem,
                            topLeft = Offset(left + step * 0.22f, split),
                            size = androidx.compose.ui.geometry.Size(step * 0.56f, (size.height - split).coerceAtLeast(0f)),
                        )
                    }
                    ChartStyle.FLOATING -> {
                        val topValue = mark.primary ?: return@forEachIndexed
                        val bottomValue = mark.secondary ?: return@forEachIndexed
                        val top = yOf(maxOf(topValue, bottomValue))
                        val bottom = yOf(minOf(topValue, bottomValue))
                        drawRect(
                            color = if (mark.highlighted) PulsePalette.blue else PulsePalette.blue.copy(alpha = 0.72f),
                            topLeft = Offset(left + step * 0.28f, top),
                            size = androidx.compose.ui.geometry.Size(step * 0.44f, (bottom - top).coerceAtLeast(2f)),
                        )
                    }
                    ChartStyle.LINE, ChartStyle.DUAL_LINE -> Unit
                }
            }
            if (style == ChartStyle.LINE || style == ChartStyle.DUAL_LINE) {
                drawSeries(points, span, yMin, PulsePalette.blue) { it.primary }
                if (style == ChartStyle.DUAL_LINE) {
                    drawSeries(points, span, yMin, PulsePalette.green) { it.secondary }
                }
            }
        }
        }
        Row(Modifier.fillMaxWidth().padding(start = 36.dp, top = 4.dp)) {
            points.forEach { mark ->
                Text(
                    mark.label,
                    color = if (mark.highlighted) PulsePalette.text else PulsePalette.muted,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 11.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    points: List<ChartMark>,
    span: Float,
    yMin: Float,
    color: Color,
    pick: (ChartMark) -> Float?,
) {
    val path = Path()
    var started = false
    val step = if (points.isEmpty()) size.width else size.width / points.size
    points.forEachIndexed { index, mark ->
        val value = pick(mark) ?: return@forEachIndexed
        val x = index * step + step / 2f
        val y = size.height * (1f - (value - yMin) / span)
        if (!started) {
            path.moveTo(x, y)
            started = true
        } else {
            path.lineTo(x, y)
        }
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(x, y))
    }
    if (started) {
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.cornerPathEffect(8f)))
    }
}

fun axisBounds(values: List<Float>, floorAtZero: Boolean = true): Pair<Float, Float> {
    if (values.isEmpty()) return 0f to 1f
    val min = values.min()
    val max = values.max()
    val pad = ((max - min) * 0.12f).coerceAtLeast(0.5f)
    val low = if (floorAtZero) (min - pad).coerceAtLeast(0f) else min - pad
    return low to max + pad
}
