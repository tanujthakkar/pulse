package app.pulse.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag as semanticsTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HealthMonitorRoute(
    onBack: () -> Unit,
    viewModel: HomeViewModel,
) {
    val content by viewModel.ui.collectAsState()
    HealthMonitorScreen(model = content?.monitor, onBack = onBack)
}

@Composable
fun HealthMonitorScreen(
    model: HealthMonitorModel?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(PulsePalette.background)
            .verticalScroll(rememberScrollState())
            .padding(PulseSpace.md),
        verticalArrangement = Arrangement.spacedBy(PulseSpace.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .size(40.dp)
                    .clickable(onClick = onBack)
                    .testTag("health_back"),
                contentAlignment = Alignment.Center,
            ) {
                PulseIcon(PulseMark.CHEVRON_LEFT, PulsePalette.text, Modifier.size(18.dp))
            }
            Text(
                "HEALTH MONITOR",
                color = PulsePalette.text,
                fontSize = PulseType.body,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f),
            )
        }
        if (model == null) {
            Text(
                "Reading stored history.",
                color = PulsePalette.muted,
                fontSize = PulseType.body,
                modifier = Modifier.testTag("health_reading"),
            )
            return@Column
        }
        HeartRateBlock(model)
        MonitorGrid(model)
    }
}

@Composable
private fun HeartRateBlock(model: HealthMonitorModel) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PulseSpace.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PulseSpace.sm)) {
            PulseIcon(PulseMark.PULSE, PulsePalette.text, Modifier.size(16.dp))
            Text("HEART RATE", color = PulsePalette.text, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, fontSize = PulseType.label)
        }
        Text(
            model.bpmText,
            color = PulsePalette.text,
            fontSize = 48.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.testTag("health_hr"),
        )
        Text("BPM", color = PulsePalette.muted, fontSize = PulseType.label, fontWeight = FontWeight.Medium)
        Text(
            model.status,
            color = PulsePalette.text,
            fontSize = PulseType.body,
            modifier = Modifier.testTag("health_status"),
        )
        HeartTrace(model.trace)
    }
}

@Composable
private fun HeartTrace(points: List<HeartPoint>) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .testTag("health_trace"),
    ) {
        val grid = PulsePalette.track
        for (step in 1..3) {
            val y = size.height * step / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        if (points.size < 2) return@Canvas
        val min = (points.minOf { it.bpm } - 8).coerceAtLeast(30).toFloat()
        val max = (points.maxOf { it.bpm } + 8).coerceAtMost(220).toFloat().coerceAtLeast(min + 1f)
        val start = points.first().tMillis
        val span = (points.last().tMillis - start).coerceAtLeast(1L).toFloat()
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = (point.tMillis - start) / span * size.width
            val y = size.height - (point.bpm - min) / (max - min) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, PulsePalette.green, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun MonitorGrid(model: HealthMonitorModel) {
    val cards = listOf(
        MonitorCard("RESPIRATORY RATE", model.respiratory, PulseMark.LUNGS, "health_rr"),
        MonitorCard("BLOOD OXYGEN (SPO2)", model.spo2, PulseMark.DROP, "health_spo2"),
        MonitorCard("RHR", model.rhr, PulseMark.HEART, "health_rhr"),
        MonitorCard("HRV", model.hrv, PulseMark.PULSE, "health_hrv"),
        MonitorCard("SKIN TEMP (FROM BASELINE)", model.skin, PulseMark.THERMOMETER, "health_temp"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(PulseSpace.sm)) {
        cards.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpace.sm), modifier = Modifier.fillMaxWidth()) {
                row.forEach { card -> MonitorTile(card, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class MonitorCard(
    val name: String,
    val value: MetricValue,
    val mark: PulseMark,
    val tag: String,
)

@Composable
private fun MonitorTile(card: MonitorCard, modifier: Modifier) {
    Column(
        modifier
            .semantics(mergeDescendants = true) { semanticsTag = card.tag }
            .background(PulsePalette.surface, RoundedCornerShape(18.dp))
            .padding(PulseSpace.md),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(PulseSpace.sm), verticalAlignment = Alignment.CenterVertically) {
            PulseIcon(card.mark, PulsePalette.muted, Modifier.size(16.dp))
            Text(card.name, color = PulsePalette.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(PulseSpace.xs)) {
            Text(
                card.value.text,
                color = PulsePalette.text,
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
            )
            if (!card.value.available) {
                Text(
                    "No data",
                    color = PulsePalette.text,
                    fontSize = PulseType.label,
                    modifier = Modifier
                        .background(PulsePalette.track, RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}
