package app.pulse.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag as semanticsTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    content: HomeContent?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(PulsePalette.background)
            .verticalScroll(rememberScrollState())
            .padding(PulseSpace.md)
            .testTag("home_screen"),
        verticalArrangement = Arrangement.spacedBy(PulseSpace.md),
    ) {
        Text(
            "Pulse",
            color = PulsePalette.text,
            fontSize = PulseType.title,
            fontWeight = FontWeight.SemiBold,
        )
        if (content == null) {
            Text(
                "Reading stored history.",
                color = PulsePalette.muted,
                fontSize = PulseType.body,
                modifier = Modifier.testTag("home_reading"),
            )
            return@Column
        }
        val banner = content.sampleBanner
        if (banner != null) {
            Text(banner, color = PulsePalette.yellow, fontSize = PulseType.label, modifier = Modifier.testTag("home_sample"))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onPrevious, enabled = content.canGoPrevious, modifier = Modifier.testTag("home_prev")) {
                Text("Prev", color = if (content.canGoPrevious) PulsePalette.text else PulsePalette.muted)
            }
            Text(
                content.dateLabel,
                color = PulsePalette.text,
                fontSize = PulseType.body,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag("home_date"),
            )
            TextButton(onClick = onNext, enabled = content.canGoNext, modifier = Modifier.testTag("home_next")) {
                Text("Next", color = if (content.canGoNext) PulsePalette.text else PulsePalette.muted)
            }
        }
        Text(
            "Source: ${content.sourceLabel}",
            color = PulsePalette.muted,
            fontSize = PulseType.label,
            modifier = Modifier.testTag("home_source"),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PulseSpace.sm),
        ) {
            PulseDial(content.recovery, Modifier.weight(1f), "home_recovery")
            PulseDial(content.strain, Modifier.weight(1f), "home_strain")
            PulseDial(content.sleep, Modifier.weight(1f), "home_sleep")
        }
        MetricCard("HRV", content.hrv, content.hrvDetail, "home_hrv")
        MetricCard("Resting HR", content.resting, content.restingDetail, "home_rhr")
        MetricCard("Sleep", content.sleepStat, content.sleepDetail, "home_sleep_hours")
        MetricCard("Steps", content.steps, content.stepsDetail, "home_steps")
        MetricCard(
            "Live",
            content.liveValue,
            "${content.liveDetail} ${content.lastSync} ${content.battery} ${content.connection}",
            "home_live",
        )
    }
}

@Composable
fun PulseDial(model: DialModel, modifier: Modifier = Modifier, tag: String) {
    Column(
        modifier.semantics(mergeDescendants = true) { semanticsTag = tag },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val tone = model.tone.color()
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(108.dp).padding(PulseSpace.xs)) {
                val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                drawArc(
                    color = PulsePalette.track,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = stroke,
                )
                if (model.progress > 0f) {
                    drawArc(
                        color = tone,
                        startAngle = 135f,
                        sweepAngle = 270f * model.progress.coerceIn(0f, 1f),
                        useCenter = false,
                        style = stroke,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    model.value,
                    color = PulsePalette.text,
                    fontSize = PulseType.body,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(model.title, color = PulsePalette.muted, fontSize = PulseType.label, textAlign = TextAlign.Center)
            }
        }
        Text(model.detail, color = PulsePalette.muted, fontSize = PulseType.label, textAlign = TextAlign.Center)
    }
}

@Composable
fun MetricCard(title: String, value: String, detail: String, tag: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { semanticsTag = tag }
            .background(PulsePalette.surface, RoundedCornerShape(16.dp))
            .padding(PulseSpace.md),
        verticalArrangement = Arrangement.spacedBy(PulseSpace.xs),
    ) {
        Text(title, color = PulsePalette.muted, fontSize = PulseType.label)
        Text(value, color = PulsePalette.text, fontSize = PulseType.title, fontWeight = FontWeight.SemiBold)
        Text(detail, color = PulsePalette.muted, fontSize = PulseType.body)
    }
}
