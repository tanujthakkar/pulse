package app.pulse.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import app.pulse.metrics.ActivityCard

@Composable
fun HomeRoute(
    onOpenActivity: (ActivityCard) -> Unit,
    onOpenHealth: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val content by viewModel.ui.collectAsState()
    HomeScreen(
        content = content,
        onPickDate = viewModel::pick,
        onOpenActivity = onOpenActivity,
        onOpenHealth = onOpenHealth,
        onSaveActivity = viewModel::saveActivity,
        onDeleteActivity = viewModel::deleteActivity,
    )
}

const val HEALTH_ROUTE: String = "health"

fun activityRoute(card: ActivityCard): String =
    "activity/${card.kind.name}/${card.startUnix}/${card.endUnix}"

const val ACTIVITY_ROUTE: String = "activity/{kind}/{start}/{end}"
