package com.telepic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.telepic.navigation.TelepicDestination
import com.telepic.navigation.TelepicNavHost
import com.telepic.navigation.icon
import com.telepic.navigation.navigateToTopLevel
import com.telepic.navigation.selectedIcon
import com.telepic.di.AppContainer
import com.telepic.settings.ThemeMode

/**
 * The Telepic application shell: a Material 3 [Scaffold] with a bottom navigation bar for
 * the five primary destinations and the navigation host filling the remaining space.
 */
@Composable
fun TelepicApp(
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
    val currentTab = TelepicDestination.topLevelOf(route)

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (currentTab != null) {
                // Google Photos-style floating bar: a rounded, elevated pill that sits above the
                // system navigation bar with side/bottom margins. Selection stays a tonal pill.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp)
                        .padding(top = 6.dp, bottom = 12.dp),
                ) {
                    NavigationBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(10.dp, RoundedCornerShape(28.dp))
                            .clip(RoundedCornerShape(28.dp)),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    ) {
                        TelepicDestination.entries.forEach { destination ->
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
            }
        },
    ) { innerPadding ->
        TelepicNavHost(
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
