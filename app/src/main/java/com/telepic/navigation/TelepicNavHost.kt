package com.telepic.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.telepic.R
import com.telepic.di.AppContainer
import com.telepic.settings.ThemeMode
import com.telepic.ui.components.ProvideSharedElementScopes
import com.telepic.ui.screens.albums.AlbumContentsScreen
import com.telepic.ui.screens.albums.AlbumContentsViewModel
import com.telepic.ui.screens.albums.AlbumsScreen
import com.telepic.ui.screens.albums.AlbumsViewModel
import com.telepic.ui.screens.backup.BackupCenterScreen
import com.telepic.ui.screens.backup.BackupViewModel
import com.telepic.ui.screens.cloud.CloudScreen
import com.telepic.ui.screens.cloud.CloudViewModel
import com.telepic.ui.screens.map.MapScreen
import com.telepic.ui.screens.map.MapViewModel
import com.telepic.ui.screens.organization.OrganizationScreen
import com.telepic.ui.screens.organization.OrganizationViewModel
import com.telepic.ui.screens.photos.PhotosScreen
import com.telepic.ui.screens.photos.PhotosViewModel
import com.telepic.ui.screens.settings.SettingsScreen
import com.telepic.ui.screens.settings.SettingsViewModel
import com.telepic.ui.screens.viewer.ViewerScreen
import com.telepic.ui.screens.viewer.ViewerViewModel
import kotlinx.coroutines.flow.combine

/**
 * Hosts the five primary destinations plus the contextual Viewer, Album contents and Backup Center.
 * Four of them are bottom-bar tabs; Settings is a destination reached from the top-right gear.
 *
 * Media identity is carried through navigation as a typed [MediaSource]: Photos and Album contents
 * open local items by MediaStore id, Cloud opens remote items by (chatId, messageId), and the two
 * never share an untyped id. The Viewer is pushed onto whichever screen opened it, so system Back
 * returns to the originating context.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TelepicNavHost(
    navController: NavHostController,
    container: AppContainer,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The shared-element host has to sit *above* the NavHost: a shared element pairs one composable in
    // the grid's destination with one in the Viewer's, and both must live inside the same transition
    // layout for the bounds to interpolate between them. The graph itself is a separate function so
    // this wrapper adds no indentation to it.
    SharedTransitionLayout(modifier = modifier) {
        TelepicNavGraph(
            sharedTransitionScope = this@SharedTransitionLayout,
            navController = navController,
            container = container,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TelepicNavGraph(
    sharedTransitionScope: SharedTransitionScope,
    navController: NavHostController,
    container: AppContainer,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    // One handoff channel for "show this photo on the map", hoisted above the graph so the Viewer's
    // Details sheet and the Map tab share it without touching the Map route (see [MapFocusRequest]).
    val mapFocusRequest = remember { MapFocusRequest() }
    NavHost(
        navController = navController,
        startDestination = TelepicDestination.Start.route,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(TelepicDestination.Photos.route) {
            // Factories are remembered per container so recomposition never hands `viewModel` a new
            // factory instance (which would otherwise rebuild the factory on every frame).
            val photosFactory = remember(container) {
                viewModelFactory {
                    initializer {
                        PhotosViewModel(
                            repository = container.localMediaRepository,
                            changeWatcher = container.mediaChangeWatcher,
                            backupStatusRepository = container.backupStatusRepository,
                            organizationRepository = container.mediaOrganizationRepository,
                            backupCoordinator = container.backupCoordinator,
                            customAlbumRepository = container.customAlbumRepository,
                        )
                    }
                }
            }
            val photosViewModel: PhotosViewModel = viewModel(factory = photosFactory)
            // The grid's tiles are the source end of the Viewer's shared-element transition, so this
            // destination publishes its scopes. Every other destination simply does not, and its tiles
            // are ordinary tiles.
            ProvideSharedElementScopes(sharedTransitionScope, this) {
                PhotosScreen(
                    viewModel = photosViewModel,
                    onMediaSelected = { media -> navController.navigate(ViewerRoute.local(media.id)) },
                    onOpenSettings = { navController.navigateToTopLevel(TelepicDestination.Settings) },
                )
            }
        }
        composable(TelepicDestination.Cloud.route) {
            val cloudFactory = remember(container) {
                viewModelFactory { initializer { CloudViewModel(container.cloudRepository) } }
            }
            val cloudViewModel: CloudViewModel = viewModel(factory = cloudFactory)
            CloudScreen(
                viewModel = cloudViewModel,
                onOpenMedia = { media -> navController.navigate(ViewerRoute.cloud(media.chatId, media.messageId)) },
                onOpenSettings = { navController.navigateToTopLevel(TelepicDestination.Settings) },
            )
        }
        composable(TelepicDestination.Albums.route) {
            val albumsFactory = remember(container) {
                viewModelFactory {
                    initializer {
                        AlbumsViewModel(container.albumRepository, container.customAlbumRepository)
                    }
                }
            }
            val albumsViewModel: AlbumsViewModel = viewModel(factory = albumsFactory)
            AlbumsScreen(
                viewModel = albumsViewModel,
                onOpenAlbum = { album -> navController.navigate(AlbumRoute.create(album.bucketId, album.title)) },
                onOpenOrganization = { kind -> navController.navigate(OrganizationRoute.create(kind)) },
                onOpenSettings = { navController.navigateToTopLevel(TelepicDestination.Settings) },
                onOpenCustomAlbum = { album ->
                    navController.navigate(AlbumRoute.createCustom(album.id, album.name))
                },
            )
        }
        composable(
            route = OrganizationRoute.PATTERN,
            arguments = listOf(navArgument(OrganizationRoute.ARG_KIND) { type = NavType.StringType }),
        ) { backStackEntry ->
            val kind = OrganizationRoute.kindOf(backStackEntry.arguments?.getString(OrganizationRoute.ARG_KIND))
            if (kind == null) {
                // Never a silent blank screen: an unparseable arg degrades to an honest fallback.
                InvalidRouteFallback(onBack = { navController.popBackStack() })
                return@composable
            }
            val organizationFactory = remember(container, kind) {
                viewModelFactory {
                    initializer {
                        OrganizationViewModel(
                            kind = kind,
                            organizationRepository = container.mediaOrganizationRepository,
                            localLookup = container.localMediaLookup,
                            deleter = container.mediaDeleter,
                        )
                    }
                }
            }
            val organizationViewModel: OrganizationViewModel = viewModel(factory = organizationFactory)
            OrganizationScreen(
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
            val bucketId = backStackEntry.arguments?.getLong(AlbumRoute.ARG_BUCKET_ID)
            if (bucketId == null) {
                InvalidRouteFallback(onBack = { navController.popBackStack() })
                return@composable
            }
            val title = backStackEntry.arguments?.getString(AlbumRoute.ARG_TITLE).orEmpty()
            val contentsFactory = remember(container, bucketId, title) {
                viewModelFactory {
                    initializer {
                        AlbumContentsViewModel(
                            source = AlbumSource.Bucket(bucketId, title),
                            albumRepository = container.albumRepository,
                            customAlbumRepository = container.customAlbumRepository,
                            backupStatusRepository = container.backupStatusRepository,
                            backupCoordinator = container.backupCoordinator,
                            organizationRepository = container.mediaOrganizationRepository,
                        )
                    }
                }
            }
            val contentsViewModel: AlbumContentsViewModel = viewModel(factory = contentsFactory)
            ProvideSharedElementScopes(sharedTransitionScope, this) {
                AlbumContentsScreen(
                    viewModel = contentsViewModel,
                    onBack = { navController.popBackStack() },
                    onMediaSelected = { media -> navController.navigate(ViewerRoute.localInBucket(media.id, bucketId)) },
                )
            }
        }
        composable(
            route = AlbumRoute.CUSTOM_PATTERN,
            arguments = listOf(
                navArgument(AlbumRoute.ARG_ALBUM_ID) { type = NavType.LongType },
                navArgument(AlbumRoute.ARG_NAME) { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStackEntry ->
            val albumId = backStackEntry.arguments?.getLong(AlbumRoute.ARG_ALBUM_ID)
            if (albumId == null) {
                InvalidRouteFallback(onBack = { navController.popBackStack() })
                return@composable
            }
            // The name is the route's *initial* value, so the header is filled on the first frame; the
            // ViewModel then observes the stored name and replaces it, so a rename cannot leave this
            // header stale.
            val albumName = backStackEntry.arguments?.getString(AlbumRoute.ARG_NAME).orEmpty()
            val contentsFactory = remember(container, albumId, albumName) {
                viewModelFactory {
                    initializer {
                        AlbumContentsViewModel(
                            source = AlbumSource.Custom(albumId, title = albumName),
                            albumRepository = container.albumRepository,
                            customAlbumRepository = container.customAlbumRepository,
                            backupStatusRepository = container.backupStatusRepository,
                            backupCoordinator = container.backupCoordinator,
                            organizationRepository = container.mediaOrganizationRepository,
                        )
                    }
                }
            }
            val contentsViewModel: AlbumContentsViewModel = viewModel(factory = contentsFactory)
            ProvideSharedElementScopes(sharedTransitionScope, this) {
                AlbumContentsScreen(
                    viewModel = contentsViewModel,
                    onBack = { navController.popBackStack() },
                    // Scoped to this album, so swiping stays inside it — the same guarantee a folder album
                    // and the curated collections already had.
                    onMediaSelected = { media ->
                        navController.navigate(ViewerRoute.localInCustomAlbum(media.id, albumId))
                    },
                )
            }
        }
        composable(TelepicDestination.Map.route) {
            val mapFactory = remember(container) {
                viewModelFactory {
                    initializer {
                        MapViewModel(
                            mapLocationRepository = container.mapLocationRepository,
                            hiddenIds = combine(
                                container.mediaOrganizationRepository.archivedIds,
                                container.mediaOrganizationRepository.trashedIds,
                            ) { archived, trashed -> archived + trashed },
                            cameraRepository = container.mapCameraRepository,
                        )
                    }
                }
            }
            val mapViewModel: MapViewModel = viewModel(factory = mapFactory)
            MapScreen(
                viewModel = mapViewModel,
                onOpenMedia = { mediaId -> navController.navigate(ViewerRoute.local(mediaId)) },
                focusRequest = mapFocusRequest,
                onOpenSettings = { navController.navigateToTopLevel(TelepicDestination.Settings) },
            )
        }
        composable(TelepicDestination.Settings.route) {
            val settingsFactory = remember(container) {
                viewModelFactory {
                    initializer {
                        SettingsViewModel(
                            telegramAuthController = container.telegramAuthController,
                            onboardingRepository = container.onboardingRepository,
                            settingsRepository = container.settingsRepository,
                            // Display scope: the library count describes what the library shows.
                            albumRepository = container.albumRepository,
                            // Backup scope: the folder picker lists what a backup would take.
                            foldersRepository = container.backupAlbumRepository,
                            backupCoordinator = container.backupCoordinator,
                            cloudRepository = container.cloudRepository,
                            appContext = checkNotNull(
                                this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                            ),
                        )
                    }
                }
            }
            val settingsViewModel: SettingsViewModel = viewModel(factory = settingsFactory)
            SettingsScreen(
                viewModel = settingsViewModel,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                onOpenBackupCenter = { navController.navigate(BackupCenterRoute.ROUTE) },
                onOpenTrash = { navController.navigate(OrganizationRoute.create(OrganizationKind.TRASH)) },
                // Settings is not a tab, so it needs its own way out; system Back still works too.
                onBack = { navController.popBackStack() },
            )
        }
        composable(BackupCenterRoute.ROUTE) {
            val backupFactory = remember(container) {
                viewModelFactory {
                    initializer {
                        BackupViewModel(
                            coordinator = container.backupCoordinator,
                            authState = container.telegramAuthController.state,
                        )
                    }
                }
            }
            val backupViewModel: BackupViewModel = viewModel(factory = backupFactory)
            BackupCenterScreen(viewModel = backupViewModel, onBack = { navController.popBackStack() })
        }
        composable(
            route = ViewerRoute.LOCAL,
            arguments = listOf(
                navArgument(ViewerRoute.ARG_MEDIA_ID) { type = NavType.LongType },
                navArgument(ViewerRoute.ARG_BUCKET_ID) { type = NavType.LongType; defaultValue = -1L },
                navArgument(ViewerRoute.ARG_ORG_KIND) { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument(ViewerRoute.ARG_ALBUM_ID) { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { backStackEntry ->
            val mediaId = backStackEntry.arguments?.getLong(ViewerRoute.ARG_MEDIA_ID) ?: 0L
            val bucketId = backStackEntry.arguments?.getLong(ViewerRoute.ARG_BUCKET_ID)
                ?.takeIf { it >= 0L }
            val orgKind = OrganizationRoute.kindOf(backStackEntry.arguments?.getString(ViewerRoute.ARG_ORG_KIND))
            val albumId = backStackEntry.arguments?.getLong(ViewerRoute.ARG_ALBUM_ID)
                ?.takeIf { it >= 0L }
            val viewerFactory = remember(container, mediaId, bucketId, orgKind, albumId) {
                viewModelFactory {
                    initializer {
                        ViewerViewModel(
                            initialSource = MediaSource.Local(mediaId),
                            localLookup = container.localMediaLookup,
                            cloudRepository = container.cloudRepository,
                            organizationRepository = container.mediaOrganizationRepository,
                            scope = ViewerRoute.scopeOf(bucketId, orgKind, albumId),
                            metadataReader = container.mediaMetadataReader,
                            backupStatusRepository = container.backupStatusRepository,
                            settingsRepository = container.settingsRepository,
                            customAlbumRepository = container.customAlbumRepository,
                            // Only the local route gets a coordinator: a cloud item *is* the backup, so
                            // the cloud viewer has nothing to back up.
                            backupCoordinator = container.backupCoordinator,
                        )
                    }
                }
            }
            val viewerViewModel: ViewerViewModel = viewModel(factory = viewerFactory)
            ProvideSharedElementScopes(sharedTransitionScope, this) {
                ViewerScreen(
                    viewModel = viewerViewModel,
                    onBack = { navController.popBackStack() },
                    onShowOnMap = { latitude, longitude ->
                        mapFocusRequest.request(latitude, longitude)
                        navController.navigateToTopLevel(TelepicDestination.Map)
                    },
                )
            }
        }
        composable(
            route = ViewerRoute.CLOUD,
            arguments = listOf(
                navArgument(ViewerRoute.ARG_CHAT_ID) { type = NavType.LongType },
                navArgument(ViewerRoute.ARG_MESSAGE_ID) { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getLong(ViewerRoute.ARG_CHAT_ID)
            val messageId = backStackEntry.arguments?.getLong(ViewerRoute.ARG_MESSAGE_ID)
            if (chatId == null || messageId == null) {
                InvalidRouteFallback(onBack = { navController.popBackStack() })
                return@composable
            }
            val viewerFactory = remember(container, chatId, messageId) {
                viewModelFactory {
                    initializer {
                        ViewerViewModel(
                            initialSource = MediaSource.Cloud(chatId, messageId),
                            localLookup = container.localMediaLookup,
                            cloudRepository = container.cloudRepository,
                            restoreRepository = container.restoreRepository,
                            settingsRepository = container.settingsRepository,
                        )
                    }
                }
            }
            val viewerViewModel: ViewerViewModel = viewModel(factory = viewerFactory)
            // Cloud items publish the scopes too, but have no matching tile, so the Viewer just appears
            // (see rememberSharedMediaModifier) rather than being special-cased here.
            ProvideSharedElementScopes(sharedTransitionScope, this) {
                ViewerScreen(viewModel = viewerViewModel, onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * Shown when a route's arguments fail to parse: an honest message plus a way out, never a
 * silent blank screen that strands the user.
 */
@Composable
private fun InvalidRouteFallback(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.nav_invalid_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onBack) {
                Text(stringResource(R.string.nav_invalid_back))
            }
        }
    }
}
