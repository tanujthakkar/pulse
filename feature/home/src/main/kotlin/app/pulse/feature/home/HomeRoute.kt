package app.pulse.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import app.pulse.metrics.ActivityCard

@Composable
fun HomeRoute(
    onOpenSettings: () -> Unit,
    onOpenActivity: (ActivityCard) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val content by viewModel.ui.collectAsState()
    HomeScreen(
        content = content,
        onOpenSettings = onOpenSettings,
        onPickDate = viewModel::pick,
        onBackToToday = viewModel::backToToday,
        onOpenActivity = onOpenActivity,
        onSaveActivity = viewModel::saveActivity,
        onDeleteActivity = viewModel::deleteActivity,
    )
}

fun activityRoute(card: ActivityCard): String =
    "activity/${card.kind.name}/${card.startUnix}/${card.endUnix}"

const val ACTIVITY_ROUTE: String = "activity/{kind}/{start}/{end}"
