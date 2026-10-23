package app.pulse

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.pulse.feature.connect.ConnectRoute
import app.pulse.feature.debug.DebugRoute
import app.pulse.feature.live.LiveRoute
import app.pulse.feature.sync.SyncRoute

@Composable
fun PulseNavHost() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    Scaffold(
        bottomBar = {
            NavigationBar {
                PulseRoute.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = current == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Text(destination.label.take(1)) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = PulseRoute.Connect.route,
            modifier = Modifier.padding(padding),
        ) {
            // ConnectRoute stays on the gate until grants are held and Bluetooth is on.
            composable(PulseRoute.Connect.route) { ConnectRoute() }
            composable(PulseRoute.Live.route) { LiveRoute() }
            composable(PulseRoute.Sync.route) { SyncRoute() }
            composable(PulseRoute.Debug.route) { DebugRoute() }
        }
    }
}
