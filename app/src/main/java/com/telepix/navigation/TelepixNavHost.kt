package com.telepix.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.telepix.di.AppContainer
import com.telepix.settings.ThemeMode
import com.telepix.ui.screens.albums.AlbumContentsScreen
import com.telepix.ui.screens.albums.AlbumContentsViewModel
import com.telepix.ui.screens.albums.AlbumsScreen
import com.telepix.ui.screens.albums.AlbumsViewModel
import com.telepix.ui.screens.backup.BackupCenterScreen
import com.telepix.ui.screens.backup.BackupViewModel
import com.telepix.ui.screens.cloud.CloudScreen
import com.telepix.ui.screens.cloud.CloudViewModel
import com.telepix.ui.screens.map.MapScreen
import com.telepix.ui.screens.photos.PhotosScreen
import com.telepix.ui.screens.photos.PhotosViewModel
import com.telepix.ui.screens.settings.SettingsScreen
import com.telepix.ui.screens.viewer.ViewerScreen
import com.telepix.ui.screens.viewer.ViewerViewModel
import kotlinx.coroutines.flow.combine

/**
 * Hosts the five primary destinations plus the contextual Viewer, Album contents and Backup Center.
 *
 * Media identity is carried through navigation as a typed [MediaSource]: Photos and Album contents
 * open local items by MediaStore id, Cloud opens remote items by (chatId, messageId), and the two
 * never share an untyped id. The Viewer is pushed onto whichever screen opened it, so system Back
 * returns to the originating context.
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
                            backupStatusRepository = container.backupStatusRepository,
                            organizationRepository = container.mediaOrganizationRepository,
                            backupCoordinator = container.backupCoordinator,
                        )
                    }
                },
            )
            PhotosScreen(
                viewModel = photosViewModel,
                onMediaSelected = { media -> navController.navigate(ViewerRoute.local(media.id)) },
                onOpenSettings = { navController.navigateToTopLevel(TelepixDestination.Settings) },
            )
        }
        composable(TelepixDestination.Cloud.route) {
            val cloudViewModel: CloudViewModel = viewModel(
                factory = viewModelFactory { initializer { CloudViewModel(container.cloudRepository) } },
            )
            CloudScreen(
                viewModel = cloudViewModel,
                onOpenMedia = { media -> navController.navigate(ViewerRoute.cloud(media.chatId, media.messageId)) },
            )
        }
        composable(TelepixDestination.Albums.route) {
            val albumsViewModel: AlbumsViewModel = viewModel(
                factory = viewModelFactory { initializer { AlbumsViewModel(container.albumRepository) } },
            )
            AlbumsScreen(
                viewModel = albumsViewModel,
                onOpenAlbum = { album -> navController.navigate(AlbumRoute.create(album.bucketId, album.title)) },
                onOpenOrganization = { kind -> navController.navigate(OrganizationRoute.create(kind)) },
            )
        }
        composable(
            route = OrganizationRoute.PATTERN,
            arguments = listOf(navArgument(OrganizationRoute.ARG_KIND) { type = NavType.StringType }),
        ) { backStackEntry ->
            val kind = OrganizationRoute.kindOf(backStackEntry.arguments?.getString(OrganizationRoute.ARG_KIND))
                ?: return@composable
            val organizationViewModel: com.telepix.ui.screens.organization.OrganizationViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        com.telepix.ui.screens.organization.OrganizationViewModel(
                            kind = kind,
                            organizationRepository = container.mediaOrganizationRepository,
                            localLookup = container.localMediaLookup,
                            deleter = container.mediaDeleter,
                        )
                    }
                },
            )
            com.telepix.ui.screens.organization.OrganizationScreen(
                viewModel = organizationViewModel,
                onBack = { navController.popBackStack() },
                onOpenMedia = { mediaId -> navController.navigate(ViewerRoute.localInCollection(mediaId, kind)) },
            )
        }
        composable(
            route = AlbumRoute.PATTERN,
            arguments = listOf(
                navArgument(AlbumRoute.ARG_BUCKET_ID) { type = NavType.LongType },
                navArgument(AlbumRoute.ARG_TITLE) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val bucketId = backStackEntry.arguments?.getLong(AlbumRoute.ARG_BUCKET_ID) ?: return@composable
            val title = backStackEntry.arguments?.getString(AlbumRoute.ARG_TITLE).orEmpty()
            val contentsViewModel: AlbumContentsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        AlbumContentsViewModel(
                            album = com.telepix.domain.media.Album(
                                bucketId = bucketId,
                                title = title,
                                coverUri = android.net.Uri.EMPTY,
                                coverIsVideo = false,
                                count = 0,
                                latestMillis = 0L,
                            ),
                            albumRepository = container.albumRepository,
                            backupStatusRepository = container.backupStatusRepository,
                        )
                    }
                },
            )
            AlbumContentsScreen(
                viewModel = contentsViewModel,
                onBack = { navController.popBackStack() },
                onMediaSelected = { media -> navController.navigate(ViewerRoute.localInBucket(media.id, bucketId)) },
            )
        }
        composable(TelepixDestination.Map.route) {
            val mapViewModel: com.telepix.ui.screens.map.MapViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        com.telepix.ui.screens.map.MapViewModel(
                            mapLocationRepository = container.mapLocationRepository,
                            hiddenIds = combine(
                                container.mediaOrganizationRepository.archivedIds,
                                container.mediaOrganizationRepository.trashedIds,
                            ) { archived, trashed -> archived + trashed },
                        )
                    }
                },
            )
            com.telepix.ui.screens.map.MapScreen(
                viewModel = mapViewModel,
                onOpenMedia = { mediaId -> navController.navigate(ViewerRoute.local(mediaId)) },
            )
        }
        composable(TelepixDestination.Settings.route) {
            SettingsScreen(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                onOpenBackupCenter = { navController.navigate(BackupCenterRoute.ROUTE) },
            )
        }
        composable(BackupCenterRoute.ROUTE) {
            val backupViewModel: BackupViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        BackupViewModel(
                            coordinator = container.backupCoordinator,
                            authState = container.telegramAuthController.state,
                        )
                    }
                },
            )
            BackupCenterScreen(viewModel = backupViewModel, onBack = { navController.popBackStack() })
        }
        composable(
            route = ViewerRoute.LOCAL,
            arguments = listOf(
                navArgument(ViewerRoute.ARG_MEDIA_ID) { type = NavType.LongType },
                navArgument(ViewerRoute.ARG_BUCKET_ID) { type = NavType.LongType; nullable = true; defaultValue = -1L },
                navArgument(ViewerRoute.ARG_ORG_KIND) { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { backStackEntry ->
            val mediaId = backStackEntry.arguments?.getLong(ViewerRoute.ARG_MEDIA_ID) ?: 0L
            val bucketId = backStackEntry.arguments?.getLong(ViewerRoute.ARG_BUCKET_ID)
                ?.takeIf { it >= 0L }
            val orgKind = OrganizationRoute.kindOf(backStackEntry.arguments?.getString(ViewerRoute.ARG_ORG_KIND))
            val viewerViewModel: ViewerViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ViewerViewModel(
                            initialSource = MediaSource.Local(mediaId),
                            localLookup = container.localMediaLookup,
                            cloudRepository = container.cloudRepository,
                            organizationRepository = container.mediaOrganizationRepository,
                            scope = ViewerRoute.scopeOf(bucketId, orgKind),
                            metadataReader = container.mediaMetadataReader,
                        )
                    }
                },
            )
            ViewerScreen(viewModel = viewerViewModel, onBack = { navController.popBackStack() })
        }
        composable(
            route = ViewerRoute.CLOUD,
            arguments = listOf(
                navArgument(ViewerRoute.ARG_CHAT_ID) { type = NavType.LongType },
                navArgument(ViewerRoute.ARG_MESSAGE_ID) { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getLong(ViewerRoute.ARG_CHAT_ID) ?: return@composable
            val messageId = backStackEntry.arguments?.getLong(ViewerRoute.ARG_MESSAGE_ID) ?: return@composable
            val viewerViewModel: ViewerViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ViewerViewModel(
                            initialSource = MediaSource.Cloud(chatId, messageId),
                            localLookup = container.localMediaLookup,
                            cloudRepository = container.cloudRepository,
                            restoreRepository = container.restoreRepository,
                        )
                    }
                },
            )
            ViewerScreen(viewModel = viewerViewModel, onBack = { navController.popBackStack() })
        }
    }
}

/**
 * Navigates to a top-level destination the same way the bottom bar does (single-top, restores the
 * per-tab back stack). Used by the Photos top-bar Settings action so it behaves identically to
 * tapping the Settings tab, without duplicating the navigation shell.
 */
private fun NavHostController.navigateToTopLevel(destination: TelepixDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
