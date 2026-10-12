package com.telepic.ui.screens.albums

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.telepic.R
import com.telepic.domain.media.LocalMedia
import com.telepic.ui.components.EmptyState
import com.telepic.ui.components.ErrorState
import com.telepic.ui.components.LoadingState
import com.telepic.ui.components.SelectionActionBar
import com.telepic.ui.components.SelectionTopBar
import com.telepic.ui.components.TransientMessageBar
import com.telepic.ui.screens.photos.MediaGrid
import com.telepic.ui.theme.TelepicTokens

/**
 * One album's contents — a paged, day-grouped grid reusing the Photos [MediaGrid] (tiles, backup
 * badges, date rail) so albums and the timeline behave identically. Tapping a cell opens the Viewer;
 * long-press enters the same multi-select + bulk-backup mode as the timeline.
 *
 * For a **user-created** album the header also carries an overflow with Rename and Delete, and the
 * selection bar offers Remove from album — the two things that make an album the user's own rather than
 * the device's. A folder album shows neither: its name comes from the filesystem and its membership
 * *is* the filesystem, so offering those actions would be offering something that cannot work.
 */
@Composable
fun AlbumContentsScreen(
    viewModel: AlbumContentsViewModel,
    onBack: () -> Unit,
    onMediaSelected: (LocalMedia) -> Unit,
    modifier: Modifier = Modifier,
) {
    val paging = viewModel.media.collectAsLazyPagingItems()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val backupStates by viewModel.backupStates.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val selectedItems by viewModel.selectedItems.collectAsStateWithLifecycle()
    val selectionReport by viewModel.selectionReport.collectAsStateWithLifecycle()
    val isBackingUp by viewModel.isBackingUp.collectAsStateWithLifecycle()
    val isRemoving by viewModel.isRemoving.collectAsStateWithLifecycle()
    val selectionActive = selectedItems.isNotEmpty()
    val customAlbumId = viewModel.customAlbumId
    val spacing = TelepicTokens.spacing
    val refresh = paging.loadState.refresh
    val hasContent = paging.itemCount > 0

    var showActions by remember { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    // Back exits selection first, before leaving the album.
    BackHandler(enabled = selectionActive) { viewModel.clearSelection() }

    Surface(modifier = modifier.fillMaxSize(), color = TelepicTokens.colors.mediaBackdrop) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (selectionActive) {
                    SelectionTopBar(
                        count = selectedItems.size,
                        onCancel = viewModel::clearSelection,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = spacing.xs, top = spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.albums_back),
                            )
                        }
                        Text(
                            text = title,
                            // Unified with ScreenHeader's default so every screen's title is 22sp.
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            // Clamped so a long album name cannot grow the header past its 48dp row.
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (customAlbumId != null) {
                            IconButton(onClick = { showActions = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription = stringResource(R.string.album_more_actions),
                                )
                            }
                            DropdownMenu(
                                expanded = showActions,
                                onDismissRequest = { showActions = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.album_rename)) },
                                    onClick = {
                                        showActions = false
                                        showRename = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.album_delete)) },
                                    onClick = {
                                        showActions = false
                                        showDeleteConfirm = true
                                    },
                                )
                            }
                        }
                    }
                }
                // Mirror the Photos timeline: an in-flight load, a genuine failure, and an empty album are
                // three distinct states. Because archived/trashed items are hidden here too, a blank grid
                // must never be the only signal.
                Box(modifier = Modifier.fillMaxSize().padding(top = spacing.xs)) {
                    when {
                        refresh is LoadState.Loading && !hasContent -> LoadingState()
                        refresh is LoadState.Error -> ErrorState(
                            title = stringResource(R.string.album_contents_error_title),
                            explanation = stringResource(R.string.album_contents_error_description),
                            onRetry = paging::retry,
                        )
                        refresh is LoadState.NotLoading && !hasContent -> EmptyState(
                            icon = Icons.Outlined.PhotoLibrary,
                            title = stringResource(R.string.album_contents_empty_title),
                            // A user-created album is empty because nothing has been added yet, which is
                            // a different thing from a folder whose media is all archived or trashed —
                            // and the two need different instructions.
                            description = stringResource(
                                if (customAlbumId != null) {
                                    R.string.album_contents_empty_custom_description
                                } else {
                                    R.string.album_contents_empty_description
                                },
                            ),
                        )
                        else -> MediaGrid(
                            media = paging,
                            backupStates = backupStates,
                            onMediaSelected = onMediaSelected,
                            selectedIds = selectedItems.keys,
                            selectionActive = selectionActive,
                            favoriteIds = favoriteIds,
                            onToggleSelect = viewModel::toggleSelection,
                            onLongSelect = viewModel::beginSelection,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // Stacked so the transient message never draws on top of the selection action bar.
            // Same honest split as the timeline: new work vs items already covered.
            Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                selectionReport?.let { report ->
                    TransientMessageBar(
                        message = stringResource(
                            R.string.photos_selection_result,
                            report.summary.queued,
                            report.summary.alreadyCovered,
                        ),
                        onDismiss = viewModel::consumeSelectionMessage,
                        // Two identical summaries render the same sentence; the report's own id
                        // keeps the second one from inheriting the first one's timer.
                        messageId = report.id,
                    )
                }
                if (selectionActive) {
                    SelectionActionBar(
                        canBackup = selectedItems.isNotEmpty(),
                        busy = isBackingUp || isRemoving,
                        onBackup = viewModel::backupSelected,
                        onCancel = viewModel::clearSelection,
                        // Only a user-created album has membership the user can change.
                        onRemoveFromAlbum = if (customAlbumId != null) {
                            viewModel::removeSelectedFromAlbum
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    if (showRename) {
        AlbumNameDialog(
            title = stringResource(R.string.album_rename_title),
            confirmLabel = stringResource(R.string.album_rename_confirm),
            initialName = title,
            onConfirm = { name ->
                showRename = false
                viewModel.rename(name)
            },
            onDismiss = { showRename = false },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.album_delete_title)) },
            text = { Text(stringResource(R.string.album_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    // Navigating away is the ViewModel's decision, taken only once the delete landed.
                    viewModel.delete(onDeleted = onBack)
                }) {
                    Text(stringResource(R.string.album_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.organization_cancel))
                }
            },
        )
    }
}
