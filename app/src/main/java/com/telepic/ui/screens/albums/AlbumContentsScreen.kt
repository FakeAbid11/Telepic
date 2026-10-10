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
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import com.telepic.ui.screens.photos.MediaGrid
import com.telepic.ui.theme.TelepicTokens

/**
 * One album's contents — a paged, day-grouped grid reusing the Photos [MediaGrid] (tiles, backup
 * badges, date rail) so albums and the timeline behave identically. Tapping a cell opens the Viewer;
 * long-press enters the same multi-select + bulk-backup mode as the timeline.
 */
@Composable
fun AlbumContentsScreen(
    viewModel: AlbumContentsViewModel,
    onBack: () -> Unit,
    onMediaSelected: (LocalMedia) -> Unit,
    modifier: Modifier = Modifier,
) {
    val paging = viewModel.media.collectAsLazyPagingItems()
    val backupStates by viewModel.backupStates.collectAsStateWithLifecycle()
    val selectedItems by viewModel.selectedItems.collectAsStateWithLifecycle()
    val selectionMessage by viewModel.selectionMessage.collectAsStateWithLifecycle()
    val selectionActive = selectedItems.isNotEmpty()
    val spacing = TelepicTokens.spacing
    val refresh = paging.loadState.refresh
    val hasContent = paging.itemCount > 0

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
                            text = viewModel.album.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                // Mirror the Photos timeline: an in-flight load, a genuine failure, and an empty album are
                // three distinct states. Because archived/trashed items are now hidden here too, a blank
                // grid must never be the only signal — §11 can legitimately empty an otherwise-full album.
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
                            description = stringResource(R.string.album_contents_empty_description),
                        )
                        else -> MediaGrid(
                            media = paging,
                            backupStates = backupStates,
                            onMediaSelected = onMediaSelected,
                            selectedIds = selectedItems.keys,
                            selectionActive = selectionActive,
                            onToggleSelect = viewModel::toggleSelection,
                            onLongSelect = viewModel::beginSelection,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            if (selectionActive) {
                SelectionActionBar(
                    canBackup = selectedItems.isNotEmpty(),
                    onBackup = viewModel::backupSelected,
                    onCancel = viewModel::clearSelection,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            // Transient feedback after a bulk enqueue; auto-cleared. Same honest split as the timeline:
            // new work vs items the engine recognized as already covered (never a fabricated upload).
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
