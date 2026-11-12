package app.pulse.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag as semanticsTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pulse.link.StrapPresence
import app.pulse.metrics.ActivityCard
import java.time.Instant
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    content: HomeContent?,
    onPickDate: (java.time.LocalDate) -> Unit,
    onOpenActivity: (ActivityCard) -> Unit,
    onOpenHealth: () -> Unit,
    onSaveActivity: (Long?, String, LocalTime, LocalTime) -> Unit,
    onDeleteActivity: (Long) -> Unit,
    onOpenMetric: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    initiallyShowCalendar: Boolean = false,
    initiallyEditing: Boolean = false,
    strap: StrapPresence = StrapPresence(),
    onOpenStrap: () -> Unit = {},
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
    LazyColumn(
        modifier
            .fillMaxSize()
            .background(PulsePalette.background)
            .padding(horizontal = PulseSpace.md)
            .testTag("home_screen"),
        verticalArrangement = Arrangement.spacedBy(PulseSpace.lg),
        contentPadding = PaddingValues(top = PulseSpace.md, bottom = 88.dp),
    ) {
        item {
            Box(Modifier.fillMaxWidth()) {
                DateHeader(
                    label = content?.headerLabel ?: "--",
                    previousEnabled = content != null,
                    nextEnabled = content != null && !content.isToday,
                    onPrevious = { content?.let { onPickDate(it.date.minusDays(1)) } },
                    onNext = { content?.let { onPickDate(it.date.plusDays(1)) } },
                    onOpenCalendar = {
                        if (content != null) {
                            visibleMonth = YearMonth.from(content.date)
                            calendarOpen = !calendarOpen
                        }
                    },
                )
                StrapStatusButton(
                    presence = strap,
                    onClick = onOpenStrap,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        if (content == null) {
            item {
            Text(
                "Reading stored history.",
                color = PulsePalette.muted,
                fontSize = PulseType.body,
                modifier = Modifier.testTag("home_reading"),
            )
            }
            return@LazyColumn
        }
        if (calendarOpen) {
            item {
            MonthCalendar(
                month = visibleMonth ?: YearMonth.from(content.date),
                onMonth = { visibleMonth = it },
                onPick = { date ->
                    calendarOpen = false
                    onPickDate(date)
                },
            )
            }
        }
        val banner = content.sampleBanner
        if (banner != null) {
            item {
            Text(banner, color = PulsePalette.yellow, fontSize = PulseType.label, modifier = Modifier.testTag("home_sample"))
            }
        }
        item { SummaryRings(content, onOpenActivity) }
        item { HealthMonitorButton(onOpenHealth) }
        item {
        MyDayBlock(
            content = content,
            onAdd = { editor = EditorState() },
            onOpen = onOpenActivity,
            onEdit = { row ->
                val start = Instant.ofEpochSecond(row.card.startUnix).atZone(content.zone).toLocalTime()
                val end = Instant.ofEpochSecond(row.card.endUnix).atZone(content.zone).toLocalTime()
                editor = EditorState(
                    id = row.card.manualId,
                    type = row.card.title,
                    startHour = start.hour.toString(),
                    startMinute = start.minute.toString(),
                    endHour = end.hour.toString(),
                    endMinute = end.minute.toString(),
                )
            },
            onDelete = onDeleteActivity,
            editor = {
                val current = editor
                if (current != null) {
                    ActivityEditor(
                        state = current,
                        onChange = { editor = it },
                        onSave = { id, type, start, end ->
                            onSaveActivity(id, type, start, end)
                            editor = null
                        },
                        onCancel = { editor = null },
                    )
                }
            },
        )
        }
        item { DashboardBlock(content, onOpenMetric = onOpenMetric) }
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
            .background(PulsePalette.track, RoundedCornerShape(16.dp))
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

private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
