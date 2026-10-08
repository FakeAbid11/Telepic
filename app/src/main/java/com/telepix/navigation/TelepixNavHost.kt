package com.telepix.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.telepix.di.AppContainer
import com.telepix.settings.ThemeMode
import com.telepix.ui.screens.albums.AlbumsScreen
import com.telepix.ui.screens.cloud.CloudScreen
import com.telepix.ui.screens.cloud.CloudViewModel
import com.telepix.ui.screens.map.MapScreen
import com.telepix.ui.screens.photos.PhotosScreen
import com.telepix.ui.screens.photos.PhotosViewModel
import com.telepix.ui.screens.settings.SettingsScreen
import com.telepix.ui.screens.viewer.ViewerScreen

/**
 * Hosts the five primary destinations plus the viewer navigation contract.
 *
 * Photos is now the real local library (Phase 3); the other destinations remain Phase 1
 * foundation screens. Tapping a media tile navigates to the viewer with only its stable id.
 */
@Composable
fun TelepixNavHost(
    navController: NavHostController,
    container: AppContainer,
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
            val photosViewModel: PhotosViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        PhotosViewModel(
                            repository = container.localMediaRepository,
                            changeWatcher = container.mediaChangeWatcher,
                        )
                    }
                },
            )
            PhotosScreen(
                viewModel = photosViewModel,
                onMediaSelected = { media ->
                    navController.navigate(ViewerRoute.create(media.id))
                },
            )
        }
        composable(TelepixDestination.Cloud.route) {
            val cloudViewModel: CloudViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { CloudViewModel(container.cloudRepository) }
                },
            )
            CloudScreen(viewModel = cloudViewModel)
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
        composable(
            route = ViewerRoute.PATTERN,
            arguments = listOf(navArgument(ViewerRoute.ARG_MEDIA_ID) { type = NavType.LongType }),
        ) { backStackEntry ->
            val mediaId = backStackEntry.arguments?.getLong(ViewerRoute.ARG_MEDIA_ID) ?: 0L
            ViewerScreen(
                mediaId = mediaId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
