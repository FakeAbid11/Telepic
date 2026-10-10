package com.telepic.ui.screens.photos

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
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
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.telepic.R
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.media.LocalMedia
import com.telepic.permissions.MediaPermissionState
import com.telepic.permissions.rememberMediaPermissionState
import com.telepic.ui.components.EmptyState
import com.telepic.ui.components.StateAction
import com.telepic.ui.theme.TelepicTokens

/**
 * The primary Telepic media experience (Phase 8): a polished, local-first Photos timeline with an
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
    val selectedItems by viewModel.selectedItems.collectAsStateWithLifecycle()
    val selectionActive = selectedItems.isNotEmpty()
    val selectionMessage by viewModel.selectionMessage.collectAsStateWithLifecycle()

    LaunchedEffect(permissionState) { viewModel.updatePermission(permissionState) }
    LaunchedEffect(permissionState.hasAccess) {
        if (permissionState.hasAccess) viewModel.refresh()
    }
    // Back exits selection first, before leaving the Photos screen.
    BackHandler(enabled = selectionActive) { viewModel.clearSelection() }

    val settingsDescription = stringResource(R.string.photos_settings)
    Surface(
        modifier = modifier.fillMaxSize(),
        color = TelepicTokens.colors.mediaBackdrop,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (selectionActive) {
                    SelectionTopBar(
                        count = selectedItems.size,
                        onCancel = viewModel::clearSelection,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = TelepicTokens.spacing.screenMargin,
                                end = TelepicTokens.spacing.xs,
                                top = TelepicTokens.spacing.lg,
                                bottom = TelepicTokens.spacing.md,
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

            // Contextual bulk-action bar for selection mode (§15). "Back up" routes through the existing
            // engine (recognition + dedup + queue + schedule) — never a parallel uploader.
            if (selectionActive) {
                SelectionActionBar(
                    canBackup = selectedItems.isNotEmpty(),
                    onBackup = viewModel::backupSelected,
                    onCancel = viewModel::clearSelection,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            // Transient feedback after a bulk enqueue; auto-cleared. Honest split of new work vs items
            // the engine recognized as already backed up / in progress (never a fabricated upload claim).
            selectionMessage?.let { summary ->
                LaunchedEffect(summary) {
                    kotlinx.coroutines.delay(2_500)
                    viewModel.consumeSelectionMessage()
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            horizontal = TelepicTokens.spacing.screenMargin,
                            vertical = TelepicTokens.spacing.lg,
                        ),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.inverseSurface,
                ) {
                    Text(
                        text = stringResource(R.string.photos_selection_result, summary.queued, summary.alreadyCovered),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = TelepicTokens.spacing.md, vertical = TelepicTokens.spacing.sm),
                    )
                }
            }
        }
    }
}

/** The selection-mode header: a close action and the live selected count in place of the title. */
@Composable
private fun SelectionTopBar(count: Int, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = TelepicTokens.spacing.xs,
                end = TelepicTokens.spacing.screenMargin,
                top = TelepicTokens.spacing.lg,
                bottom = TelepicTokens.spacing.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.photos_selection_cancel),
            )
        }
        Text(
            text = stringResource(R.string.photos_selection_count, count),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The bottom bulk-action bar shown while selecting: a Back up button and Cancel. The selected count
 * lives only in the top bar to avoid a duplicated number. */
@Composable
private fun SelectionActionBar(
    canBackup: Boolean,
    onBackup: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = TelepicTokens.spacing.md, vertical = TelepicTokens.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TelepicTokens.spacing.sm, Alignment.End),
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.photos_selection_cancel))
            }
            TextButton(onClick = onBackup, enabled = canBackup) {
                Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.photos_selection_backup),
                    modifier = Modifier.padding(start = TelepicTokens.spacing.xs),
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
    val selectedItems by viewModel.selectedItems.collectAsStateWithLifecycle()
    val selectedIds = selectedItems.keys
    val selectionActive = selectedItems.isNotEmpty()

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
                isRefreshing = refresh is LoadState.Loading && hasContent && !selectionActive,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                MediaGrid(
                    media = paging,
                    backupStates = backupStates,
                    onMediaSelected = onMediaSelected,
                    selectedIds = selectedIds,
                    selectionActive = selectionActive,
                    onToggleSelect = viewModel::toggleSelection,
                    onLongSelect = viewModel::beginSelection,
                )
            }
        }

        // Partial access honesty (§31): a subtle banner shown as soon as limited access is in
        // effect — it never claims a full library, and appears whether or not items are loaded yet.
        if (permissionState == MediaPermissionState.Partial) {
            PartialAccessBanner(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = TelepicTokens.spacing.screenMargin, vertical = TelepicTokens.spacing.sm),
            )
        }

        // A refresh failure keeps loaded photos and offers a retry (§48).
        if (refreshFailed && hasContent) {
            RefreshErrorBanner(
                onRetry = { paging.retry() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(TelepicTokens.spacing.md),
            )
        }
    }
}

/** Lightweight placeholder grid for the first load — avoids a giant blocking spinner (§45). */
@Composable
private fun PhotosSkeleton(modifier: Modifier = Modifier) {
    val spacing = TelepicTokens.spacing
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
    val spacing = TelepicTokens.spacing
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
            modifier = Modifier.padding(horizontal = TelepicTokens.spacing.md, vertical = TelepicTokens.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TelepicTokens.spacing.sm),
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
