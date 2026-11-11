package app.pulse.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag as semanticsTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.metrics.ActivityCard
import app.pulse.metrics.ActivityKind

private val sleepBrush = Brush.sweepGradient(
    listOf(
        androidx.compose.ui.graphics.Color(0xFF5B21B6),
        androidx.compose.ui.graphics.Color(0xFFDDD6FE),
        androidx.compose.ui.graphics.Color(0xFF7C3AED),
        androidx.compose.ui.graphics.Color(0xFF5B21B6),
    ),
)

private val strainBrush = Brush.sweepGradient(
    listOf(
        androidx.compose.ui.graphics.Color(0xFF1E3A8A),
        androidx.compose.ui.graphics.Color(0xFFBFDBFE),
        androidx.compose.ui.graphics.Color(0xFF2563EB),
        androidx.compose.ui.graphics.Color(0xFF1E3A8A),
    ),
)

@Composable
fun DateHeader(
    label: String,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(CircleShape)
                .background(PulsePalette.surface)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderArrow(PulseMark.CHEVRON_LEFT, "home_prev", previousEnabled, onPrevious)
            Text(
                label,
                color = PulsePalette.text,
                fontSize = PulseType.body,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
                modifier = Modifier
                    .clickable(onClick = onOpenCalendar)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("home_header"),
            )
            HeaderArrow(PulseMark.CHEVRON, "home_next", nextEnabled, onNext)
        }
    }
}

@Composable
private fun HeaderArrow(
    mark: PulseMark,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        PulseIcon(
            mark,
            if (enabled) PulsePalette.text else PulsePalette.muted.copy(alpha = 0.35f),
            Modifier.size(14.dp),
        )
    }
}

@Composable
fun SummaryRings(
    content: HomeContent,
    onOpenActivity: (ActivityCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = PulseSpace.sm)
            .testTag("home_dials"),
        horizontalArrangement = Arrangement.spacedBy(PulseSpace.md),
    ) {
        SummaryRing(content.sleep, "home_sleep", Modifier.weight(1f)) {
            val start = content.sleepStartUnix ?: return@SummaryRing
            val row = content.activities.firstOrNull { it.card.kind == ActivityKind.SLEEP && it.card.startUnix == start }
            if (row != null) onOpenActivity(row.card)
        }
        SummaryRing(content.recovery, "home_recovery", Modifier.weight(1f)) {}
        SummaryRing(content.strain, "home_strain", Modifier.weight(1f)) {}
    }
}

@Composable
fun SummaryRing(
    model: SummaryRingModel,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .semantics(mergeDescendants = true) { semanticsTag = tag }
            .clickable(onClick = onClick)
            .padding(vertical = PulseSpace.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(96.dp)) {
                val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                drawArc(
                    color = PulsePalette.track,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = stroke,
                )
                if (model.progress > 0f) {
                    val sweep = 360f * model.progress.coerceIn(0f, 1f)
                    if (model.gradient && model.tone == DialTone.PURPLE) {
                        drawArc(brush = sleepBrush, startAngle = -90f, sweepAngle = sweep, useCenter = false, style = stroke)
                    } else if (model.gradient && model.tone == DialTone.BLUE) {
                        drawArc(brush = strainBrush, startAngle = -90f, sweepAngle = sweep, useCenter = false, style = stroke)
                    } else {
                        drawArc(
                            color = model.tone.color(),
                            startAngle = -90f,
                            sweepAngle = sweep,
                            useCenter = false,
                            style = stroke,
                        )
                    }
                }
            }
            Text(
                model.value,
                color = PulsePalette.text,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                model.title.uppercase(),
                color = PulsePalette.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            PulseIcon(PulseMark.CHEVRON, PulsePalette.muted, Modifier.size(10.dp))
        }
        val caption = model.caption
        if (caption != null) {
            Text(
                caption,
                color = PulsePalette.muted,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                lineHeight = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
fun HealthMonitorButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PulsePalette.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = PulseSpace.md, vertical = 18.dp)
            .testTag("home_health"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PulseSpace.sm)) {
            PulseIcon(PulseMark.PULSE, PulsePalette.text, Modifier.size(18.dp))
            Text(
                "HEALTH MONITOR",
                color = PulsePalette.text,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                fontSize = PulseType.body,
            )
        }
        PulseIcon(PulseMark.CHEVRON, PulsePalette.muted, Modifier.size(16.dp))
    }
}

@Composable
fun MyDayBlock(
    content: HomeContent,
    onAdd: () -> Unit,
    onOpen: (ActivityCard) -> Unit,
    onEdit: (HomeActivity) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
    editor: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().testTag("home_my_day"), verticalArrangement = Arrangement.spacedBy(PulseSpace.sm)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("My Day", color = PulsePalette.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(PulsePalette.surface)
                    .clickable(onClick = onAdd)
                    .testTag("my_day_add"),
                contentAlignment = Alignment.Center,
            ) {
                PulseIcon(PulseMark.PLUS, PulsePalette.text, Modifier.size(18.dp))
            }
        }
        editor()
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(PulsePalette.surface)
                .padding(PulseSpace.md),
            verticalArrangement = Arrangement.spacedBy(PulseSpace.sm),
        ) {
            Text(
                "ACTIVITIES",
                color = PulsePalette.muted,
                fontSize = PulseType.label,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            if (content.activities.isEmpty()) {
                Text(
                    "No activities",
                    color = PulsePalette.muted,
                    fontSize = PulseType.body,
                    modifier = Modifier.testTag("activity_empty"),
                )
            }
            content.activities.forEach { row ->
                ActivityLine(row, onOpen, onEdit, onDelete)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PulsePalette.track)
                    .clickable(onClick = onAdd)
                    .padding(vertical = 14.dp)
                    .testTag("activity_add"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "ADD ACTIVITY",
                    color = PulsePalette.text,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    fontSize = PulseType.label,
                )
            }
        }
    }
}

@Composable
private fun ActivityLine(
    row: HomeActivity,
    onOpen: (ActivityCard) -> Unit,
    onEdit: (HomeActivity) -> Unit,
    onDelete: (Long) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { semanticsTag = "activity_${row.card.startUnix}" }
            .clickable { onOpen(row.card) }
            .padding(vertical = PulseSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PulseSpace.md),
    ) {
        Column(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(row.tileTone.color()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PulseIcon(
                if (row.card.kind == ActivityKind.SLEEP) PulseMark.MOON else PulseMark.RUN,
                PulsePalette.text,
                Modifier.size(16.dp),
            )
            Text(row.tile, color = PulsePalette.text, fontWeight = FontWeight.Bold, fontSize = PulseType.label)
        }
        Column(Modifier.weight(1f)) {
            Text(
                row.card.title.uppercase(),
                color = PulsePalette.text,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                fontSize = PulseType.body,
            )
            val id = row.card.manualId
            if (row.card.kind == ActivityKind.MANUAL && id != null) {
                Row {
                    Text(
                        "Edit",
                        color = PulsePalette.muted,
                        fontSize = PulseType.label,
                        modifier = Modifier
                            .clickable { onEdit(row) }
                            .padding(end = 12.dp)
                            .testTag("activity_edit_$id"),
                    )
                    Text(
                        "Delete",
                        color = PulsePalette.muted,
                        fontSize = PulseType.label,
                        modifier = Modifier
                            .clickable { onDelete(id) }
                            .testTag("activity_delete_$id"),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            val start = if (row.dayPrefix == null) row.startLabel else "${row.dayPrefix} ${row.startLabel}"
            Text(start, color = PulsePalette.muted, fontSize = PulseType.label, textAlign = TextAlign.End)
            Text(row.endLabel, color = PulsePalette.muted, fontSize = PulseType.label, textAlign = TextAlign.End)
        }
    }
}

@Composable
fun DashboardBlock(content: HomeContent, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("home_dashboard"), verticalArrangement = Arrangement.spacedBy(PulseSpace.sm)) {
        Text("My Dashboard", color = PulsePalette.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        if (content.dashboard.isEmpty()) {
            Text(
                "No stored history for this day.",
                color = PulsePalette.muted,
                fontSize = PulseType.body,
                modifier = Modifier.testTag("dash_empty"),
            )
        }
        content.dashboard.forEach { card ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { semanticsTag = card.tag }
                    .clip(RoundedCornerShape(18.dp))
                    .background(PulsePalette.surface)
                    .padding(horizontal = PulseSpace.md, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulseIcon(markFor(card.tag), PulsePalette.muted, Modifier.size(18.dp))
                Spacer(Modifier.size(PulseSpace.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        card.name,
                        color = PulsePalette.text,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp,
                        fontSize = PulseType.label,
                    )
                    val note = card.note
                    if (note != null) {
                        Text(note, color = PulsePalette.muted, fontSize = 11.sp)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            card.value,
                            color = PulsePalette.text,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val trend = card.trend
                        if (trend != null) {
                            PulseIcon(
                                if (trend == Trend.UP) PulseMark.ARROW_UP else PulseMark.ARROW_DOWN,
                                PulsePalette.muted,
                                Modifier.size(14.dp).padding(start = 2.dp),
                            )
                        }
                    }
                    val comparison = card.comparison
                    if (comparison != null) {
                        Text(comparison, color = PulsePalette.muted, fontSize = PulseType.label)
                    }
                }
            }
        }
    }
}

private fun markFor(tag: String): PulseMark = when (tag) {
    "dash_steps" -> PulseMark.STEPS
    "dash_sleep_debt" -> PulseMark.MOON
    "dash_hrv" -> PulseMark.PULSE
    else -> PulseMark.HEART
}
