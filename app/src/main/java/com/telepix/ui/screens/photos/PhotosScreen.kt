package com.telepix.ui.screens.photos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.telepix.R
import com.telepix.domain.backup.MediaBackupVisualState
import com.telepix.domain.media.LocalMedia
import com.telepix.permissions.MediaPermissionState
import com.telepix.permissions.rememberMediaPermissionState
import com.telepix.ui.components.EmptyState
import com.telepix.ui.components.StateAction
import com.telepix.ui.theme.TelepixTokens

/**
 * The primary Telepix media experience (Phase 8): a polished, local-first Photos timeline with an
 * adaptive grid, day grouping, a date rail and backup indicators — plus a top bar with Settings.
 *
 * A pure presentation layer: it reads the paged stream and the backup-status snapshot from
 * [PhotosViewModel] and never queries Room, Telegram or the hasher itself. Permission guidance
 * reuses the Phase 2/3 model; the empty / loading / error / partial states stay distinct (§29).
 */
@Composable
fun PhotosScreen(
    viewModel: PhotosViewModel,
    onMediaSelected: (LocalMedia) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    permissionStateOverride: MediaPermissionState? = null,
) {
    val controller = rememberMediaPermissionState()
    val permissionState = permissionStateOverride ?: controller.state
    val backupStates by viewModel.backupStates.collectAsStateWithLifecycle()

    LaunchedEffect(permissionState) { viewModel.updatePermission(permissionState) }
    LaunchedEffect(permissionState.hasAccess) {
        if (permissionState.hasAccess) viewModel.refresh()
    }

    val settingsDescription = stringResource(R.string.photos_settings)
    Surface(
        modifier = modifier.fillMaxSize(),
        color = TelepixTokens.colors.mediaBackdrop,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = TelepixTokens.spacing.screenMargin,
                        end = TelepixTokens.spacing.xs,
                        top = TelepixTokens.spacing.lg,
                        bottom = TelepixTokens.spacing.md,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.photos_title),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.semantics { contentDescription = settingsDescription },
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = settingsDescription)
                }
            }

            when {
                permissionState.hasAccess -> MediaRegion(
                    viewModel = viewModel,
                    permissionState = permissionState,
                    backupStates = backupStates,
                    onMediaSelected = onMediaSelected,
                )
                permissionState == MediaPermissionState.PermanentlyDenied -> EmptyState(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.photos_permission_blocked_title),
                    description = stringResource(R.string.photos_permission_blocked_description),
                    primaryAction = StateAction(
                        label = stringResource(R.string.photos_open_settings),
                        onClick = controller.openAppSettings,
                    ),
                )
                else -> EmptyState(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.photos_permission_title),
                    description = stringResource(R.string.photos_permission_description),
                    primaryAction = StateAction(
                        label = stringResource(R.string.photos_allow_access),
                        onClick = controller.requestPermission,
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaRegion(
    viewModel: PhotosViewModel,
    permissionState: MediaPermissionState,
    backupStates: Map<Long, MediaBackupVisualState>,
    onMediaSelected: (LocalMedia) -> Unit,
) {
    val paging = viewModel.media.collectAsLazyPagingItems()
    val refresh = paging.loadState.refresh
    val hasContent = paging.itemCount > 0
    val isInitialLoading = refresh is LoadState.Loading && !hasContent
    val refreshFailed = refresh is LoadState.Error

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isInitialLoading -> PhotosSkeleton()
            refresh is LoadState.NotLoading && !hasContent -> EmptyState(
                icon = Icons.Outlined.PhotoLibrary,
                title = stringResource(R.string.photos_no_media_title),
                description = stringResource(R.string.photos_no_media_description),
                primaryAction = StateAction(
                    label = stringResource(R.string.photos_refresh),
                    onClick = viewModel::refresh,
                ),
            )
            else -> PullToRefreshBox(
                isRefreshing = refresh is LoadState.Loading && hasContent,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                MediaGrid(
                    media = paging,
                    backupStates = backupStates,
                    onMediaSelected = onMediaSelected,
                )
            }
        }

        // Partial access honesty (§31): a subtle banner — never claims a full library.
        if (permissionState == MediaPermissionState.Partial && hasContent) {
            PartialAccessBanner(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = TelepixTokens.spacing.screenMargin, vertical = TelepixTokens.spacing.sm),
            )
        }

        // A refresh failure keeps loaded photos and offers a retry (§48).
        if (refreshFailed && hasContent) {
            RefreshErrorBanner(
                onRetry = { paging.retry() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(TelepixTokens.spacing.md),
            )
        }
    }
}

/** Lightweight placeholder grid for the first load — avoids a giant blocking spinner (§45). */
@Composable
private fun PhotosSkeleton(modifier: Modifier = Modifier) {
    val spacing = TelepixTokens.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.gridGutter),
        verticalArrangement = Arrangement.spacedBy(spacing.gridGutter),
    ) {
        repeat(4) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.gridGutter)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(spacing.gridItemRadius))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }
        }
    }
}

@Composable
private fun PartialAccessBanner(modifier: Modifier = Modifier) {
    val spacing = TelepixTokens.spacing
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.photos_partial_access_banner),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RefreshErrorBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = TelepixTokens.spacing.md, vertical = TelepixTokens.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TelepixTokens.spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.photos_refresh_failed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.photos_retry))
            }
        }
    }
}
