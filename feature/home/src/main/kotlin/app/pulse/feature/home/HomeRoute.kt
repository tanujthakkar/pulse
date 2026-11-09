package app.pulse.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun HomeRoute(viewModel: HomeViewModel = hiltViewModel()) {
    val content by viewModel.ui.collectAsState()
    HomeScreen(content = content, onPrevious = viewModel::previous, onNext = viewModel::next)
}
