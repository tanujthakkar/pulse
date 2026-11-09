package app.pulse.feature.day

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun DayRoute(viewModel: DayViewModel = hiltViewModel()) {
    val report by viewModel.report.collectAsState()
    DayScreen(report = report, onRefresh = viewModel::refresh)
}
