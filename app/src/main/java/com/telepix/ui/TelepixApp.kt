package com.telepix.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.telepix.navigation.TelepixDestination
import com.telepix.navigation.TelepixNavHost
import com.telepix.navigation.icon
import com.telepix.navigation.selectedIcon
import com.telepix.di.AppContainer
import com.telepix.settings.ThemeMode

/**
 * The Telepix application shell: a Material 3 [Scaffold] with a bottom navigation bar for
 * the five primary destinations and the navigation host filling the remaining space.
 */
@Composable
fun TelepixApp(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    // Only a top-level tab route selects a tab; detail/immersive screens (viewer, album contents,
    // organization, backup) resolve to null so the bar neither mis-highlights Photos nor overlays them.
    val currentTab = TelepixDestination.topLevelOf(route)

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (currentTab != null) {
                NavigationBar {
                    TelepixDestination.entries.forEach { destination ->
                        val selected = destination == currentTab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTopLevel(destination) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) destination.selectedIcon else destination.icon,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        TelepixNavHost(
            navController = navController,
            container = container,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        )
    }
}

/**
 * Standard top-level destination navigation: single-top, restores prior state, and keeps the
 * start destination on the back stack so Back from any tab returns home instead of exiting.
 */
private fun NavHostController.navigateToTopLevel(destination: TelepixDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
