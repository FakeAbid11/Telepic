package com.telepix.ui.screens.albums

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.telepix.R
import com.telepix.domain.media.LocalMedia
import com.telepix.ui.components.EmptyState
import com.telepix.ui.components.ErrorState
import com.telepix.ui.components.LoadingState
import com.telepix.ui.screens.photos.MediaGrid
import com.telepix.ui.theme.TelepixTokens

/**
 * One album's contents — a paged, day-grouped grid reusing the Photos [MediaGrid] (tiles, backup
 * badges, date rail) so albums and the timeline behave identically. Tapping a cell opens the Viewer.
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
    val spacing = TelepixTokens.spacing
    val refresh = paging.loadState.refresh
    val hasContent = paging.itemCount > 0

    Surface(modifier = modifier.fillMaxSize(), color = TelepixTokens.colors.mediaBackdrop) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
