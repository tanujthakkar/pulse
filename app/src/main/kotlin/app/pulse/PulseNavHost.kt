package app.pulse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.pulse.feature.connect.ConnectRoute
import app.pulse.feature.debug.DebugRoute
import app.pulse.feature.edgeimport.EdgeImportRoute
import app.pulse.feature.home.ACTIVITY_ROUTE
import app.pulse.feature.home.ActivityDetailRoute
import app.pulse.feature.home.HomeRoute
import app.pulse.feature.home.PulsePalette
import app.pulse.feature.home.activityRoute

@Composable
fun PulseNavHost() {
    val navController = rememberNavController()
    Box(
        Modifier
            .fillMaxSize()
            .background(PulsePalette.background),
    ) {
        NavHost(
            navController = navController,
            startDestination = PulseRoute.Home.route,
        ) {
            composable(PulseRoute.Home.route) {
                HomeRoute(
                    onOpenSettings = { navController.navigate(PulseRoute.More.route) },
                    onOpenActivity = { card -> navController.navigate(activityRoute(card)) },
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
    }
}
