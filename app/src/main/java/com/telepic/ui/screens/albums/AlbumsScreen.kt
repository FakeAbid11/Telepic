package com.telepic.ui.screens.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.telepic.ui.components.LoadingState
import com.telepic.ui.components.ScreenHeader
import com.telepic.ui.components.SettingsAction
import com.telepic.ui.components.StateAction
import com.telepic.ui.theme.TelepicTokens

/** A Material 3 FAB's own size. Named because the grid has to reserve exactly this much room for it. */
private const val FAB_SIZE_DP = 56

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
    val spacing = TelepicTokens.spacing
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

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
                when {
                    !permissionState.hasAccess -> EmptyState(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.albums_permission_title),
                        description = stringResource(R.string.albums_permission_description),
                        primaryAction = if (permissionState == MediaPermissionState.PermanentlyDenied) {
                            StateAction(stringResource(R.string.photos_open_settings), controller.openAppSettings)
                        } else {
                            StateAction(stringResource(R.string.photos_allow_access), controller.requestPermission)
                        },
                    )
                    currentStatus is AlbumsStatus.Loading -> LoadingState()
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
                    )
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
}

@Composable
private fun AlbumGrid(
    customAlbums: List<CustomAlbum>,
    folderAlbums: List<Album>,
    onOpenAlbum: (Album) -> Unit,
    onOpenCustomAlbum: (CustomAlbum) -> Unit,
) {
    val spacing = TelepicTokens.spacing
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
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
 */
@Composable
private fun AlbumTile(
    title: String,
    count: Int,
    coverModel: Any?,
    openLabel: String,
    onClick: () -> Unit,
) {
    val spacing = TelepicTokens.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(spacing.gridItemRadius))
            .padding(bottom = spacing.sm)
            .semantics(mergeDescendants = true) { contentDescription = openLabel }
            .clickable(onClick = onClick),
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
}

/**
 * Contextual entry points to Favorites / Archive / Trash. These are deliberately reached from an
 * existing screen rather than adding bottom-navigation destinations (per the PRD).
 */
@Composable
private fun LibraryShortcuts(onOpenOrganization: (OrganizationKind) -> Unit) {
    val spacing = TelepicTokens.spacing
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.xs),
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
