package com.telepic.ui.screens.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.telepic.R
import com.telepic.domain.media.Album
import com.telepic.navigation.OrganizationKind
import com.telepic.permissions.MediaPermissionState
import com.telepic.permissions.rememberMediaPermissionState
import com.telepic.ui.components.EmptyState
import com.telepic.ui.components.ErrorState
import com.telepic.ui.components.LoadingState
import com.telepic.ui.components.ScreenHeader
import com.telepic.ui.components.StateAction
import com.telepic.ui.theme.TelepicTokens

/**
 * Local Albums (Phase 9): real MediaStore buckets rendered as an adaptive grid of covers, names and
 * counts, reusing the Telepic states and theme. Permission-aware like Photos, and honest — denied and
 * genuinely-empty are different. Tapping an album opens its contents.
 */
@Composable
fun AlbumsScreen(
    viewModel: AlbumsViewModel,
    onOpenAlbum: (Album) -> Unit,
    onOpenOrganization: (OrganizationKind) -> Unit,
    modifier: Modifier = Modifier,
    permissionStateOverride: MediaPermissionState? = null,
) {
    val controller = rememberMediaPermissionState()
    val permissionState = permissionStateOverride ?: controller.state
    val status by viewModel.status.collectAsStateWithLifecycle()
    val spacing = TelepicTokens.spacing

    LaunchedEffect(permissionState.hasAccess) { if (permissionState.hasAccess) viewModel.refresh() }

    Surface(modifier = modifier.fillMaxSize(), color = TelepicTokens.colors.mediaBackdrop) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(title = stringResource(R.string.albums_title))
            if (permissionState.hasAccess) {
                LibraryShortcuts(onOpenOrganization)
            }
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
                status is AlbumsStatus.Loading -> LoadingState()
                status is AlbumsStatus.Error -> ErrorState(
                    title = stringResource(R.string.albums_error_title),
                    explanation = stringResource(R.string.albums_error_description),
                    onRetry = viewModel::refresh,
                )
                status is AlbumsStatus.Empty -> EmptyState(
                    icon = Icons.Outlined.Collections,
                    title = stringResource(R.string.albums_empty_title),
                    description = stringResource(R.string.albums_empty_description),
                    primaryAction = StateAction(stringResource(R.string.photos_refresh), viewModel::refresh),
                )
                status is AlbumsStatus.Ready -> AlbumGrid((status as AlbumsStatus.Ready).albums, onOpenAlbum)
            }
        }
    }
}

@Composable
private fun AlbumGrid(albums: List<Album>, onOpenAlbum: (Album) -> Unit) {
    val spacing = TelepicTokens.spacing
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(spacing.gridGutter),
        horizontalArrangement = Arrangement.spacedBy(spacing.gridGutter),
        verticalArrangement = Arrangement.spacedBy(spacing.gridGutter),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.bucketId }) { album ->
            AlbumCard(album = album, onClick = { onOpenAlbum(album) })
        }
    }
}

@Composable
private fun AlbumCard(album: Album, onClick: () -> Unit) {
    val spacing = TelepicTokens.spacing
    val openLabel = stringResource(R.string.albums_open_album, album.title)
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
                model = album.coverUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Column(modifier = Modifier.padding(top = spacing.xs)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.albums_item_count, album.count),
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
