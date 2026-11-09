package app.pulse.feature.day

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * One scrollable report for every stored local day.
 *
 * [report] is the export text: formulas, coverage, gaps, and the intermediate
 * values behind each score. The scores are approximate.
 */
@Composable
fun DayScreen(
    report: String,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Days", style = MaterialTheme.typography.titleLarge)
        Text(
            "Approximate formulas from strap seconds and Edge import seconds. Strap wins on overlap. Not WHOOP scores.",
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = onRefresh,
            modifier = Modifier
                .padding(top = 12.dp)
                .testTag("day_refresh"),
        ) {
            Text("Refresh")
        }
        SelectionContainer {
            Text(
                report,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .testTag("day_report"),
            )
        }
    }
}
