package com.telepic.navigation

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
import com.telepic.di.AppContainer
import com.telepic.settings.ThemeMode
import com.telepic.ui.screens.albums.AlbumContentsScreen
import com.telepic.ui.screens.albums.AlbumContentsViewModel
import com.telepic.ui.screens.albums.AlbumsScreen
import com.telepic.ui.screens.albums.AlbumsViewModel
import com.telepic.ui.screens.backup.BackupCenterScreen
import com.telepic.ui.screens.backup.BackupViewModel
import com.telepic.ui.screens.cloud.CloudScreen
import com.telepic.ui.screens.cloud.CloudViewModel
import com.telepic.ui.screens.map.MapScreen
import com.telepic.ui.screens.photos.PhotosScreen
import com.telepic.ui.screens.photos.PhotosViewModel
import com.telepic.ui.screens.settings.SettingsScreen
import com.telepic.ui.screens.viewer.ViewerScreen
import com.telepic.ui.screens.viewer.ViewerViewModel
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
fun TelepicNavHost(
    navController: NavHostController,
    container: AppContainer,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TelepicDestination.Start.route,
        modifier = modifier,
    ) {
        composable(TelepicDestination.Photos.route) {
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
                onOpenSettings = { navController.navigateToTopLevel(TelepicDestination.Settings) },
            )
        }
        composable(TelepicDestination.Cloud.route) {
            val cloudViewModel: CloudViewModel = viewModel(
                factory = viewModelFactory { initializer { CloudViewModel(container.cloudRepository) } },
            )
            CloudScreen(
                viewModel = cloudViewModel,
                onOpenMedia = { media -> navController.navigate(ViewerRoute.cloud(media.chatId, media.messageId)) },
            )
        }
        composable(TelepicDestination.Albums.route) {
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
            val organizationViewModel: com.telepic.ui.screens.organization.OrganizationViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        com.telepic.ui.screens.organization.OrganizationViewModel(
                            kind = kind,
                            organizationRepository = container.mediaOrganizationRepository,
                            localLookup = container.localMediaLookup,
                            deleter = container.mediaDeleter,
                        )
                    }
                },
            )
            com.telepic.ui.screens.organization.OrganizationScreen(
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
                            album = com.telepic.domain.media.Album(
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
        composable(TelepicDestination.Map.route) {
            val mapViewModel: com.telepic.ui.screens.map.MapViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        com.telepic.ui.screens.map.MapViewModel(
                            mapLocationRepository = container.mapLocationRepository,
                            hiddenIds = combine(
                                container.mediaOrganizationRepository.archivedIds,
                                container.mediaOrganizationRepository.trashedIds,
                            ) { archived, trashed -> archived + trashed },
                        )
                    }
                },
            )
            com.telepic.ui.screens.map.MapScreen(
                viewModel = mapViewModel,
                onOpenMedia = { mediaId -> navController.navigate(ViewerRoute.local(mediaId)) },
            )
        }
        composable(TelepicDestination.Settings.route) {
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
