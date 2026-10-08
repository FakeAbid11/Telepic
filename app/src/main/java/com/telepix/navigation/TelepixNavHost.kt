package com.telepix.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.telepix.settings.ThemeMode
import com.telepix.ui.screens.albums.AlbumsScreen
import com.telepix.ui.screens.cloud.CloudScreen
import com.telepix.ui.screens.map.MapScreen
import com.telepix.ui.screens.photos.PhotosScreen
import com.telepix.ui.screens.settings.SettingsScreen

/**
 * Hosts the five primary destinations.
 *
 * Each destination currently renders a high-quality Phase 1 foundation screen. Later phases
 * replace the screen contents without changing this navigation graph.
 */
@Composable
fun TelepixNavHost(
    navController: NavHostController,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TelepixDestination.Start.route,
        modifier = modifier,
    ) {
        composable(TelepixDestination.Photos.route) {
            PhotosScreen()
        }
        composable(TelepixDestination.Cloud.route) {
            CloudScreen()
        }
        composable(TelepixDestination.Albums.route) {
            AlbumsScreen()
        }
        composable(TelepixDestination.Map.route) {
            MapScreen()
        }
        composable(TelepixDestination.Settings.route) {
            SettingsScreen(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
            )
        }
    }
}
