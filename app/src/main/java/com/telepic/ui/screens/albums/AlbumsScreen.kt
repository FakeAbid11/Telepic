package com.telepic.ui.screens.albums

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.telepic.R
import com.telepic.domain.media.Album
import com.telepic.domain.media.CustomAlbum
import com.telepic.navigation.OrganizationKind
import com.telepic.permissions.MediaPermissionState
import com.telepic.permissions.rememberForcedMediaPermissionState
import com.telepic.permissions.rememberMediaPermissionState
import com.telepic.ui.components.EmptyState
import com.telepic.ui.components.ErrorState
import com.telepic.ui.components.MediaGridSkeleton
import com.telepic.ui.components.ScreenHeader
import com.telepic.ui.components.SettingsAction
import com.telepic.ui.components.StateAction
import com.telepic.ui.components.TelepicHaptics
import com.telepic.ui.components.rememberTelepicHaptics
import com.telepic.ui.theme.TelepicTokens

/** A Material 3 FAB's own size. Named because the grid has to reserve exactly this much room for it. */
private const val FAB_SIZE_DP = 56

/** Adaptive minimum cell size for the album grid. Shared with the first-load skeleton so the two match. */
private val ALBUM_TILE_MIN_SIZE = 160.dp

/**
 * Albums: the user's own albums first, then the device's folders, in one adaptive grid.
 *
 * The two are shown in one grid with section headings rather than on two screens, because from the
 * user's point of view both are "an album" — the distinction that matters is which ones they made and
 * can therefore edit, and a heading says that more cheaply than a navigation step.
 *
 * "New album" is a FAB rather than a header action: it is the primary thing you come here to do that
 * you cannot do anywhere else, and the header's top-right slot is already spoken for by Settings. It
 * appears only once media access is granted, because an album you cannot put anything into is not worth
 * offering.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    viewModel: AlbumsViewModel,
    onOpenAlbum: (Album) -> Unit,
    onOpenOrganization: (OrganizationKind) -> Unit,
    modifier: Modifier = Modifier,
    permissionStateOverride: MediaPermissionState? = null,
    onOpenSettings: () -> Unit = {},
    onOpenCustomAlbum: (CustomAlbum) -> Unit = {},
) {
    // One controller per composition: the real (launcher-owning) one unless the caller forces a state.
    val controller = if (permissionStateOverride != null) {
        rememberForcedMediaPermissionState(permissionStateOverride)
    } else {
        rememberMediaPermissionState()
    }
    val permissionState = controller.state
    val status by viewModel.status.collectAsStateWithLifecycle()
    val customAlbums by viewModel.customAlbums.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val haptics = rememberTelepicHaptics()
    val spacing = TelepicTokens.spacing
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    // The long-press menu's target is held as an id plus the name the dialog opens with, rather than
    // as a CustomAlbum: both survive Activity recreation, a data class would not, and the album list
    // is a live flow so the entry can be gone by the time the dialog is confirmed.
    var renameAlbumId by rememberSaveable { mutableStateOf<Long?>(null) }
    var renameInitialName by rememberSaveable { mutableStateOf("") }
    var deleteAlbumId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(permissionState.hasAccess) { if (permissionState.hasAccess) viewModel.refresh() }

    Surface(modifier = modifier.fillMaxSize(), color = TelepicTokens.colors.mediaBackdrop) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                ScreenHeader(
                    title = stringResource(R.string.albums_title),
                    trailing = { SettingsAction(onClick = onOpenSettings) },
                )
                if (permissionState.hasAccess) {
                    LibraryShortcuts(onOpenOrganization)
                }
                val currentStatus = status
                // "Nothing here" has to account for both kinds, or an empty device holding one
                // user-created album would be told it has no albums at all.
                val folderAlbums = (currentStatus as? AlbumsStatus.Ready)?.albums.orEmpty()
                val nothingAtAll = currentStatus is AlbumsStatus.Empty && customAlbums.isEmpty()
                if (!permissionState.hasAccess) {
                    EmptyState(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.albums_permission_title),
                        description = stringResource(R.string.albums_permission_description),
                        primaryAction = if (permissionState == MediaPermissionState.PermanentlyDenied) {
                            StateAction(stringResource(R.string.photos_open_settings), controller.openAppSettings)
                        } else {
                            StateAction(stringResource(R.string.photos_allow_access), controller.requestPermission)
                        },
                    )
                } else {
                    // Pull-to-refresh is outside the `when` so it also works on the empty and error
                    // states, where a user who has just taken a photo is most likely to reach for it.
                    // The indicator is driven by a real in-flight flag rather than by the load state,
                    // so opening the screen does not look like a refresh.
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        when {
                            currentStatus is AlbumsStatus.Loading ->
                                MediaGridSkeleton(minCellSize = ALBUM_TILE_MIN_SIZE)
                            currentStatus is AlbumsStatus.Error -> ErrorState(
                                title = stringResource(R.string.albums_error_title),
                                explanation = stringResource(R.string.albums_error_description),
                                onRetry = viewModel::refresh,
                            )
                            nothingAtAll -> EmptyState(
                                icon = Icons.Outlined.Collections,
                                title = stringResource(R.string.albums_empty_title),
                                description = stringResource(R.string.albums_empty_description),
                                primaryAction = StateAction(stringResource(R.string.photos_refresh), viewModel::refresh),
                            )
                            else -> AlbumGrid(
                                customAlbums = customAlbums,
                                folderAlbums = folderAlbums,
                                onOpenAlbum = onOpenAlbum,
                                onOpenCustomAlbum = onOpenCustomAlbum,
                                onRenameAlbum = { album ->
                                    renameInitialName = album.name
                                    renameAlbumId = album.id
                                },
                                onDeleteAlbum = { album -> deleteAlbumId = album.id },
                                haptics = haptics,
                            )
                        }
                    }
                }
            }

            if (permissionState.hasAccess) {
                FloatingActionButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(spacing.lg),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.albums_create),
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        AlbumNameDialog(
            title = stringResource(R.string.albums_create_title),
            confirmLabel = stringResource(R.string.albums_create_confirm),
            onConfirm = { name ->
                showCreateDialog = false
                viewModel.createAlbum(name)
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    // Both dialogs are the same two the album's own overflow uses (AlbumContentsScreen), reached here
    // so the user does not have to open an album just to rename or delete it. Nothing is reported
    // back: `customAlbums` is an observed flow, so a rename or delete that did not land simply leaves
    // the grid as it was — the same contract `createAlbum` has.
    val pendingRename = renameAlbumId
    if (pendingRename != null) {
        AlbumNameDialog(
            title = stringResource(R.string.album_rename_title),
            confirmLabel = stringResource(R.string.album_rename_confirm),
            initialName = renameInitialName,
            onConfirm = { name ->
                renameAlbumId = null
                viewModel.renameAlbum(pendingRename, name)
            },
            onDismiss = { renameAlbumId = null },
        )
    }

    val pendingDelete = deleteAlbumId
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteAlbumId = null },
            title = { Text(stringResource(R.string.album_delete_title)) },
            // The body already says the photos stay on the device and in the Telegram backup — which
            // is exactly the thing a user deleting an album needs told.
            text = { Text(stringResource(R.string.album_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteAlbumId = null
                    viewModel.deleteAlbum(pendingDelete)
                }) {
                    Text(stringResource(R.string.album_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteAlbumId = null }) {
                    Text(stringResource(R.string.organization_cancel))
                }
            },
        )
    }
}

@Composable
private fun AlbumGrid(
    customAlbums: List<CustomAlbum>,
    folderAlbums: List<Album>,
    onOpenAlbum: (Album) -> Unit,
    onOpenCustomAlbum: (CustomAlbum) -> Unit,
    onRenameAlbum: (CustomAlbum) -> Unit,
    onDeleteAlbum: (CustomAlbum) -> Unit,
    haptics: TelepicHaptics,
) {
    val spacing = TelepicTokens.spacing
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = ALBUM_TILE_MIN_SIZE),
        contentPadding = PaddingValues(
            start = spacing.gridGutter,
            end = spacing.gridGutter,
            top = spacing.gridGutter,
            // Clears the create FAB, so the last row is never hidden behind it.
            bottom = FAB_SIZE_DP.dp + spacing.lg + spacing.lg,
        ),
        horizontalArrangement = Arrangement.spacedBy(spacing.gridGutter),
        verticalArrangement = Arrangement.spacedBy(spacing.gridGutter),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (customAlbums.isNotEmpty()) {
            item(key = "header_custom", span = { GridItemSpan(maxLineSpan) }) {
                SectionLabel(stringResource(R.string.albums_section_mine))
            }
            // Keys are prefixed per section: a custom album id and a bucket id are both Longs, and the
            // two sections share one grid's key space.
            items(customAlbums, key = { "custom_${it.id}" }) { album ->
                AlbumTile(
                    title = album.name,
                    count = album.count,
                    coverModel = album.coverUri,
                    openLabel = stringResource(R.string.albums_open_album, album.name),
                    onClick = { onOpenCustomAlbum(album) },
                    // Only a user-created album gets the menu: its name and membership are the user's,
                    // so rename and delete mean something. A folder album's name comes from the
                    // filesystem and its contents *are* the filesystem, so both actions would be
                    // offering something that cannot work — the same rule the album's own overflow
                    // follows (see AlbumContentsScreen).
                    onRename = { onRenameAlbum(album) },
                    onDelete = { onDeleteAlbum(album) },
                    haptics = haptics,
                )
            }
        }
        if (folderAlbums.isNotEmpty()) {
            item(key = "header_folders", span = { GridItemSpan(maxLineSpan) }) {
                SectionLabel(stringResource(R.string.albums_section_folders))
            }
            items(folderAlbums, key = { "folder_${it.bucketId}" }) { album ->
                AlbumTile(
                    title = album.title,
                    count = album.count,
                    coverModel = album.coverUri,
                    openLabel = stringResource(R.string.albums_open_album, album.title),
                    onClick = { onOpenAlbum(album) },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val spacing = TelepicTokens.spacing
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = spacing.xs,
            end = spacing.xs,
            top = spacing.md,
            bottom = spacing.xxs,
        ),
    )
}

/**
 * One album cell, shared by both sections.
 *
 * A null [coverModel] is a real case — a user-created album with nothing in it yet — and Coil draws
 * nothing for it, so the cell's own background shows through as an honest empty cover rather than a
 * stand-in image for a photo that does not exist.
 *
 * A long press opens Rename / Delete for a user-created album ([onRename] / [onDelete] non-null); a
 * folder album passes neither and is tap-only. Long press is the same gesture the media grids use to
 * enter selection, and [onLongClickLabel] is what makes it reachable by TalkBack rather than a
 * gesture only a sighted user can discover.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTile(
    title: String,
    count: Int,
    coverModel: Any?,
    openLabel: String,
    onClick: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    haptics: TelepicHaptics? = null,
) {
    val spacing = TelepicTokens.spacing
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onRename != null || onDelete != null
    val menuLabel = stringResource(R.string.album_more_actions)

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(spacing.gridItemRadius))
                .padding(bottom = spacing.sm)
                .semantics(mergeDescendants = true) { contentDescription = openLabel }
                .combinedClickable(
                    onClick = onClick,
                    onLongClickLabel = if (hasMenu) menuLabel else null,
                    onLongClick = if (hasMenu) {
                        {
                            haptics?.selectionStart()
                            menuOpen = true
                        }
                    } else {
                        null
                    },
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = spacing.gridItemRadius, topEnd = spacing.gridItemRadius))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = coverModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
            Column(modifier = Modifier.padding(top = spacing.xs)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralStringResource(R.plurals.albums_item_count, count, count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Anchored to the tile itself (the Box wraps the Column) rather than to the grid, so it opens
        // over the album the user pressed. Composed inside the tile so each cell owns its own state —
        // a single hoisted menu would have to carry which album it was opened for.
        if (hasMenu) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                onRename?.let { rename ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.album_rename)) },
                        onClick = {
                            menuOpen = false
                            rename()
                        },
                    )
                }
                onDelete?.let { delete ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.album_delete)) },
                        onClick = {
                            menuOpen = false
                            delete()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Contextual entry points to Favorites / Archive / Trash. These are deliberately reached from an
 * existing screen rather than adding bottom-navigation destinations (per the PRD).
 */
@Composable
private fun LibraryShortcuts(onOpenOrganization: (OrganizationKind) -> Unit) {
    val spacing = TelepicTokens.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.xs)
            // Scrollable rather than clipped. Three text buttons come to roughly 300dp, which fits a
            // 360dp phone but not a 320dp one, and not a 360dp one at a large display font — and a
            // plain Row neither wraps nor scrolls, so Trash simply became unreachable. The selection
            // bars already scroll for exactly this reason.
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        TextButton(onClick = { onOpenOrganization(OrganizationKind.FAVORITES) }) {
            Text(stringResource(R.string.library_favorites))
        }
        TextButton(onClick = { onOpenOrganization(OrganizationKind.ARCHIVE) }) {
            Text(stringResource(R.string.library_archive))
        }
        TextButton(onClick = { onOpenOrganization(OrganizationKind.TRASH) }) {
            Text(stringResource(R.string.library_trash))
        }
    }
}
