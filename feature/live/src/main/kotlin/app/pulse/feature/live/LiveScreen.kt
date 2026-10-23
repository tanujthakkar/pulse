package app.pulse.feature.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun LiveScreen(ui: LiveUi, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("live_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Live", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = ui.status,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("live_status"),
        )
        Text(
            text = ui.bpm,
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.testTag("live_bpm"),
        )
        Text(text = "bpm", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "R-R ${ui.rr}",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("live_rr"),
        )
        val contact = ui.contact
        if (contact != null) {
            Text(
                text = contact,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("live_contact"),
            )
        }
        if (ui.sparkline.size >= 2) {
            BpmSparkline(
                values = ui.sparkline,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("live_sparkline"),
            )
        }
    }
}

@Composable
private fun BpmSparkline(values: List<Int>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.secondary
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        val low = values.min().toFloat()
        val high = values.max().toFloat()
        val span = (high - low).coerceAtLeast(1f)
        val step = if (values.size == 1) 0f else size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, bpm ->
            val x = step * index
            val y = if (high == low) {
                size.height / 2f
            } else {
                size.height - ((bpm - low) / span) * size.height
            }
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
