package app.pulse.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.pulse.metrics.ActivityCard
import app.pulse.metrics.ActivityKind
import java.time.Instant
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    content: HomeContent?,
    onOpenSettings: () -> Unit,
    onPickDate: (java.time.LocalDate) -> Unit,
    onBackToToday: () -> Unit,
    onOpenActivity: (ActivityCard) -> Unit,
    onSaveActivity: (Long?, String, LocalTime, LocalTime) -> Unit,
    onDeleteActivity: (Long) -> Unit,
    modifier: Modifier = Modifier,
    initiallyShowCalendar: Boolean = false,
    initiallyEditing: Boolean = false,
) {
    var calendarOpen by remember { mutableStateOf(initiallyShowCalendar) }
    var visibleMonth by remember {
        mutableStateOf(if (initiallyShowCalendar) content?.let { YearMonth.from(it.date) } else null)
    }
    var editor by remember {
        mutableStateOf(
            if (initiallyEditing) {
                EditorState(type = "Run", startHour = "7", startMinute = "30", endHour = "8", endMinute = "15")
            } else {
                null
            },
        )
    }
    Column(
        modifier
            .fillMaxSize()
            .background(PulsePalette.background)
            .verticalScroll(rememberScrollState())
            .padding(PulseSpace.md)
            .testTag("home_screen"),
        verticalArrangement = Arrangement.spacedBy(PulseSpace.md),
    ) {
        Box(Modifier.fillMaxWidth()) {
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.CenterStart).testTag("home_settings"),
            ) {
                Text("⚙", color = PulsePalette.text)
            }
            TextButton(
                onClick = {
                    if (content != null) {
                        visibleMonth = YearMonth.from(content.date)
                        calendarOpen = !calendarOpen
                    }
                },
                modifier = Modifier.align(Alignment.Center).testTag("home_header"),
            ) {
                Text(
                    content?.headerLabel ?: "Today",
                    color = PulsePalette.text,
                    fontSize = PulseType.title,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (content != null && !content.isToday) {
                TextButton(
                    onClick = {
                        calendarOpen = false
                        onBackToToday()
                    },
                    modifier = Modifier.align(Alignment.CenterEnd).testTag("home_today"),
                ) {
                    Text("Today", color = PulsePalette.text)
                }
            }
        }
        if (content == null) {
            Text(
                "Reading stored history.",
                color = PulsePalette.muted,
                fontSize = PulseType.body,
                modifier = Modifier.testTag("home_reading"),
            )
            return@Column
        }
        if (calendarOpen) {
            MonthCalendar(
                month = visibleMonth ?: YearMonth.from(content.date),
                onMonth = { visibleMonth = it },
                onPick = { date ->
                    calendarOpen = false
                    onPickDate(date)
                },
            )
        }
        val banner = content.sampleBanner
        if (banner != null) {
            Text(banner, color = PulsePalette.yellow, fontSize = PulseType.label, modifier = Modifier.testTag("home_sample"))
        }
        Text(
            content.sourceLabel,
            color = PulsePalette.muted,
            fontSize = PulseType.label,
            modifier = Modifier.testTag("home_source"),
        )
        Row(
            modifier = Modifier.fillMaxWidth().testTag("home_dials"),
            horizontalArrangement = Arrangement.spacedBy(PulseSpace.sm),
        ) {
            PulseDial(content.sleep, Modifier.weight(1f), "home_sleep")
            PulseDial(content.recovery, Modifier.weight(1f), "home_recovery")
            PulseDial(content.strain, Modifier.weight(1f), "home_strain")
        }
        ActivitySection(
            content = content,
            editor = editor,
            onEditor = { editor = it },
            onOpenActivity = onOpenActivity,
            onSaveActivity = onSaveActivity,
            onDeleteActivity = onDeleteActivity,
        )
        MetricCard("HRV", content.hrv, "home_hrv")
        MetricCard("Resting HR", content.resting, "home_rhr")
        MetricCard("Sleep", content.sleepStat, "home_sleep_hours")
        MetricCard("Steps", content.steps, "home_steps")
        MetricCard("Live", content.liveValue, "home_live")
        MetricCard("Battery", content.battery, "home_battery")
        MetricCard("Last sync", content.lastSync, "home_sync")
    }
}

@Composable
private fun ActivitySection(
    content: HomeContent,
    editor: EditorState?,
    onEditor: (EditorState?) -> Unit,
    onOpenActivity: (ActivityCard) -> Unit,
    onSaveActivity: (Long?, String, LocalTime, LocalTime) -> Unit,
    onDeleteActivity: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PulseSpace.sm), modifier = Modifier.testTag("home_activities")) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Activities", color = PulsePalette.text, fontSize = PulseType.body, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { onEditor(EditorState()) }, modifier = Modifier.testTag("activity_add")) {
                Text("Add", color = PulsePalette.text)
            }
        }
        if (editor != null) {
            ActivityEditor(
                state = editor,
                onChange = onEditor,
                onSave = { id, type, start, end ->
                    onSaveActivity(id, type, start, end)
                    onEditor(null)
                },
                onCancel = { onEditor(null) },
            )
        }
        if (content.activities.isEmpty()) {
            Text("No activities this day.", color = PulsePalette.muted, fontSize = PulseType.body, modifier = Modifier.testTag("activity_empty"))
        }
        content.activities.forEach { row ->
            ActivityRow(
                row = row,
                onOpen = { onOpenActivity(row.card) },
                onEdit = {
                    val start = Instant.ofEpochSecond(row.card.startUnix).atZone(content.zone).toLocalTime()
                    val end = Instant.ofEpochSecond(row.card.endUnix).atZone(content.zone).toLocalTime()
                    onEditor(
                        EditorState(
                            id = row.card.manualId,
                            type = row.card.title,
                            startHour = start.hour.toString(),
                            startMinute = start.minute.toString(),
                            endHour = end.hour.toString(),
                            endMinute = end.minute.toString(),
                        ),
                    )
                },
                onDelete = { id -> onDeleteActivity(id) },
            )
        }
    }
}

@Composable
private fun ActivityRow(
    row: HomeActivity,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { semanticsTag = "activity_${row.card.startUnix}" }
            .background(PulsePalette.surface, RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .padding(PulseSpace.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.card.title, color = PulsePalette.text, fontSize = PulseType.body, fontWeight = FontWeight.Medium)
            Text(row.timeLabel, color = PulsePalette.muted, fontSize = PulseType.label)
        }
        val id = row.card.manualId
        if (row.card.kind == ActivityKind.MANUAL && id != null) {
            TextButton(onClick = onEdit, modifier = Modifier.testTag("activity_edit_$id")) {
                Text("Edit", color = PulsePalette.text)
            }
            TextButton(onClick = { onDelete(id) }, modifier = Modifier.testTag("activity_delete_$id")) {
                Text("Delete", color = PulsePalette.text)
            }
        }
    }
}

private data class EditorState(
    val id: Long? = null,
    val type: String = "",
    val startHour: String = "",
    val startMinute: String = "",
    val endHour: String = "",
    val endMinute: String = "",
    val error: String? = null,
)

@Composable
private fun ActivityEditor(
    state: EditorState,
    onChange: (EditorState) -> Unit,
    onSave: (Long?, String, LocalTime, LocalTime) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(PulsePalette.surface, RoundedCornerShape(16.dp))
            .padding(PulseSpace.md)
            .testTag("activity_editor"),
        verticalArrangement = Arrangement.spacedBy(PulseSpace.sm),
    ) {
        OutlinedTextField(
            value = state.type,
            onValueChange = { onChange(state.copy(type = it, error = null)) },
            label = { Text("Type") },
            modifier = Modifier.fillMaxWidth().testTag("activity_type"),
        )
        TimeFields("Start", state.startHour, state.startMinute, "activity_start") { hour, minute ->
            onChange(state.copy(startHour = hour, startMinute = minute, error = null))
        }
        TimeFields("End", state.endHour, state.endMinute, "activity_end") { hour, minute ->
            onChange(state.copy(endHour = hour, endMinute = minute, error = null))
        }
        val error = state.error
        if (error != null) {
            Text(error, color = PulsePalette.red, fontSize = PulseType.label, modifier = Modifier.testTag("activity_error"))
        }
        Row {
            TextButton(
                onClick = {
                    val start = clockTime(state.startHour, state.startMinute)
                    val end = clockTime(state.endHour, state.endMinute)
                    if (state.type.isBlank() || start == null || end == null) {
                        onChange(state.copy(error = "Enter a type and a start and end time."))
                    } else if (start == end) {
                        onChange(state.copy(error = "End time has to be different from the start."))
                    } else {
                        onSave(state.id, state.type, start, end)
                    }
                },
                modifier = Modifier.testTag("activity_save"),
            ) {
                Text("Save", color = PulsePalette.text)
            }
            TextButton(onClick = onCancel, modifier = Modifier.testTag("activity_cancel")) {
                Text("Cancel", color = PulsePalette.muted)
            }
        }
    }
}

@Composable
private fun TimeFields(
    label: String,
    hour: String,
    minute: String,
    tag: String,
    onChange: (String, String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PulseSpace.sm)) {
        Text(label, color = PulsePalette.muted, fontSize = PulseType.label)
        OutlinedTextField(
            value = hour,
            onValueChange = { onChange(it, minute) },
            label = { Text("Hour") },
            modifier = Modifier.weight(1f).testTag("${tag}_hour"),
        )
        OutlinedTextField(
            value = minute,
            onValueChange = { onChange(hour, it) },
            label = { Text("Minute") },
            modifier = Modifier.weight(1f).testTag("${tag}_minute"),
        )
    }
}

private fun clockTime(hourText: String, minuteText: String): LocalTime? {
    val hour = hourText.toIntOrNull() ?: return null
    val minute = minuteText.toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return LocalTime.of(hour, minute)
}

@Composable
private fun MonthCalendar(
    month: YearMonth,
    onMonth: (YearMonth) -> Unit,
    onPick: (java.time.LocalDate) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(PulsePalette.surface, RoundedCornerShape(16.dp))
            .padding(PulseSpace.sm)
            .testTag("home_calendar"),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onMonth(month.minusMonths(1)) }, modifier = Modifier.testTag("cal_prev")) {
                Text("Prev", color = PulsePalette.text)
            }
            Text(
                MONTH.format(month),
                color = PulsePalette.text,
                fontSize = PulseType.body,
                modifier = Modifier.testTag("cal_month"),
            )
            TextButton(onClick = { onMonth(month.plusMonths(1)) }, modifier = Modifier.testTag("cal_next")) {
                Text("Next", color = PulsePalette.text)
            }
        }
        val offset = month.atDay(1).dayOfWeek.value % 7
        val days = month.lengthOfMonth()
        val rows = (offset + days + 6) / 7
        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (column in 0 until 7) {
                    val day = row * 7 + column - offset + 1
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (day in 1..days) {
                            TextButton(
                                onClick = { onPick(month.atDay(day)) },
                                modifier = Modifier.testTag("cal_day_$day"),
                            ) {
                                Text(day.toString(), color = PulsePalette.text)
                            }
                        }
                    }
                }
            }
        }
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
    }
}

@Composable
fun MetricCard(title: String, value: String, tag: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { semanticsTag = tag }
            .background(PulsePalette.surface, RoundedCornerShape(16.dp))
            .padding(PulseSpace.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = PulsePalette.muted, fontSize = PulseType.body)
        Text(value, color = PulsePalette.text, fontSize = PulseType.body, fontWeight = FontWeight.SemiBold)
    }
}

private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
