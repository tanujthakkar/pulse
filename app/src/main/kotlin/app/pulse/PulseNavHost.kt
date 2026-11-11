package app.pulse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.pulse.feature.connect.ConnectRoute
import app.pulse.feature.debug.DebugRoute
import app.pulse.feature.edgeimport.EdgeImportRoute
import app.pulse.feature.home.ACTIVITY_ROUTE
import app.pulse.feature.home.ActivityDetailRoute
import app.pulse.feature.home.HEALTH_ROUTE
import app.pulse.feature.home.HealthMonitorRoute
import app.pulse.feature.home.HomeRoute
import app.pulse.feature.home.HomeTab
import app.pulse.feature.home.PulseBottomBar
import app.pulse.feature.home.PulsePalette
import app.pulse.feature.home.activityRoute

@Composable
fun PulseNavHost(onSimulationChanged: () -> Unit = {}) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tab = when (route) {
        PulseRoute.Home.route -> HomeTab.HOME
        PulseRoute.More.route -> HomeTab.MORE
        else -> null
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(PulsePalette.background),
    ) {
        NavHost(
            navController = navController,
            startDestination = PulseRoute.Home.route,
            modifier = Modifier.padding(bottom = if (tab == HomeTab.MORE) 80.dp else 0.dp),
        ) {
            composable(PulseRoute.Home.route) {
                HomeRoute(
                    onOpenActivity = { card -> navController.navigate(activityRoute(card)) },
                    onOpenHealth = { navController.navigate(HEALTH_ROUTE) },
                )
            }
            composable(HEALTH_ROUTE) { entry ->
                val parent = remember(entry) { navController.getBackStackEntry(PulseRoute.Home.route) }
                HealthMonitorRoute(
                    onBack = { navController.popBackStack() },
                    viewModel = hiltViewModel(parent),
                )
            }
            composable(ACTIVITY_ROUTE) {
                ActivityDetailRoute(onBack = { navController.popBackStack() })
            }
            composable(PulseRoute.More.route) {
                MoreRoute(
                    onConnect = { navController.navigate(PulseRoute.Connect.route) },
                    onDebug = { navController.navigate(PulseRoute.Debug.route) },
                    onImport = { navController.navigate(IMPORT_ROUTE) },
                    onSimulationChanged = onSimulationChanged,
                )
            }
            composable(PulseRoute.Connect.route) { ConnectRoute() }
            composable(PulseRoute.Debug.route) {
                DebugRoute(onOpenImport = { navController.navigate(IMPORT_ROUTE) })
            }
            composable(IMPORT_ROUTE) {
                EdgeImportRoute(onBack = { navController.popBackStack() })
            }
        }
        if (tab != null) {
            PulseBottomBar(
                selected = tab,
                onHome = {
                    if (route != PulseRoute.Home.route) {
                        navController.popBackStack(PulseRoute.Home.route, inclusive = false)
                    }
                },
                onMore = {
                    if (route != PulseRoute.More.route) {
                        navController.navigate(PulseRoute.More.route) { launchSingleTop = true }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
