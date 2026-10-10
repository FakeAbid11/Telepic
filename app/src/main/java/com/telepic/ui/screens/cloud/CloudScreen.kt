package com.telepic.ui.screens.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.telepic.R
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudMediaType
import com.telepic.domain.cloud.CloudPreviewState
import com.telepic.domain.cloud.CloudStatus
import com.telepic.domain.cloud.CloudUiState
import com.telepic.domain.media.DurationLabel
import com.telepic.ui.components.EmptyState
import com.telepic.ui.components.ErrorState
import com.telepic.ui.components.LoadingState
import com.telepic.ui.components.ScreenHeader
import com.telepic.ui.components.StateAction
import com.telepic.ui.theme.TelepicTokens
import java.io.File
import java.util.Locale

/** Real Telegram cloud screen (Phase 5). Browsing is preview-only; tapping opens the shared Viewer. */
@Composable
fun CloudScreen(
    viewModel: CloudViewModel,
    onOpenMedia: (CloudMedia) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val previews by viewModel.previews.collectAsStateWithLifecycle()
    val spacing = TelepicTokens.spacing

    Surface(modifier = modifier.fillMaxSize(), color = TelepicTokens.colors.mediaBackdrop) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScreenHeader(
                    title = stringResource(R.string.cloud_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::refresh) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.cloud_refresh),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            DestinationStatusBar(uiState)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                CloudContent(
                    uiState = uiState,
                    previews = previews,
                    onLoadPreview = viewModel::loadPreview,
                    onRetryPreview = viewModel::retryPreview,
                    onOpen = onOpenMedia,
                    onRetry = viewModel::refresh,
                )
            }
        }
    }
}

@Composable
private fun DestinationStatusBar(uiState: CloudUiState) {
    val spacing = TelepicTokens.spacing
    val connected = uiState.status is CloudStatus.Ready || uiState.status is CloudStatus.Refreshing ||
        uiState.status is CloudStatus.Empty
    Text(
        text = uiState.destinationTitle ?: stringResource(R.string.cloud_settingup_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screenMargin, vertical = spacing.xxs),
    )
    Text(
        text = stringResource(if (connected) R.string.cloud_status_connected else R.string.cloud_status_setting_up),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = spacing.screenMargin),
    )
}

@Composable
private fun CloudContent(
    uiState: CloudUiState,
    previews: Map<Pair<Long, Long>, CloudPreviewState>,
    onLoadPreview: (CloudMedia) -> Unit,
    onRetryPreview: (CloudMedia) -> Unit,
    onOpen: (CloudMedia) -> Unit,
    onRetry: () -> Unit,
) {
    // Read the status once: the branches below smart-cast this local instead of re-reading
    // `uiState.status` and casting (a re-read could observe a different value than the check did).
    val cloudStatus = uiState.status
    when (cloudStatus) {
        CloudStatus.Initializing, CloudStatus.Connecting -> LoadingState()
        CloudStatus.NotAuthenticated -> EmptyState(
            icon = Icons.Outlined.Lock,
            title = stringResource(R.string.cloud_notconnected_title),
            description = stringResource(R.string.cloud_notconnected_body),
        )
        CloudStatus.Offline -> EmptyState(
            icon = Icons.Outlined.CloudOff,
            title = stringResource(R.string.cloud_offline_title),
            description = stringResource(R.string.cloud_offline_body),
            primaryAction = StateAction(stringResource(R.string.cloud_retry), onRetry),
        )
        CloudStatus.DestinationMissing -> EmptyState(
            icon = Icons.Outlined.CloudSync,
            title = stringResource(R.string.cloud_settingup_title),
            description = stringResource(R.string.cloud_settingup_body),
            primaryAction = StateAction(stringResource(R.string.cloud_retry), onRetry),
        )
        is CloudStatus.DestinationInvalid -> EmptyState(
            icon = Icons.Outlined.CloudOff,
            title = stringResource(R.string.cloud_settingup_title),
            description = cloudStatus.reason,
            primaryAction = StateAction(stringResource(R.string.cloud_retry), onRetry),
        )
        is CloudStatus.Failed -> ErrorState(
            title = stringResource(R.string.cloud_error_title),
            explanation = cloudStatus.message.ifBlank {
                stringResource(R.string.cloud_error_body)
            },
            onRetry = onRetry,
        )
        CloudStatus.Empty -> EmptyState(
            icon = Icons.Outlined.Cloud,
            title = stringResource(R.string.cloud_empty_title),
            description = stringResource(R.string.cloud_empty_body),
        )
        CloudStatus.Refreshing -> if (uiState.hasMedia) {
            CloudGrid(uiState.media, previews, onLoadPreview, onRetryPreview, onOpen)
        } else {
            LoadingState()
        }
        CloudStatus.Ready -> CloudGrid(uiState.media, previews, onLoadPreview, onRetryPreview, onOpen)
    }
}

@Composable
private fun CloudGrid(
    media: List<CloudMedia>,
    previews: Map<Pair<Long, Long>, CloudPreviewState>,
    onLoadPreview: (CloudMedia) -> Unit,
    onRetryPreview: (CloudMedia) -> Unit,
    onOpen: (CloudMedia) -> Unit,
) {
    val spacing = TelepicTokens.spacing
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = spacing.gridTileMinSize),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(spacing.gridGutter),
        horizontalArrangement = Arrangement.spacedBy(spacing.gridGutter),
        verticalArrangement = Arrangement.spacedBy(spacing.gridGutter),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(media, key = { "${it.chatId}_${it.messageId}" }) { item ->
            CloudTile(
                media = item,
                previewState = previews[item.chatId to item.messageId],
                onLoadPreview = { onLoadPreview(item) },
                onRetryPreview = { onRetryPreview(item) },
                onClick = { onOpen(item) },
            )
        }
    }
}

@Composable
private fun CloudTile(
    media: CloudMedia,
    previewState: CloudPreviewState?,
    onLoadPreview: () -> Unit,
    onRetryPreview: () -> Unit,
    onClick: () -> Unit,
) {
    val spacing = TelepicTokens.spacing
    val tileLabel = stringResource(
        when (media.mediaType) {
            CloudMediaType.VIDEO -> R.string.cloud_media_video
            CloudMediaType.GIF -> R.string.cloud_media_gif
            CloudMediaType.IMAGE -> R.string.cloud_media_image
        },
    )
    // Announce the item with its type plus a preview state only when the state is informative.
    val statusLabel: String? = when (previewState) {
        is CloudPreviewState.Loading -> stringResource(R.string.cloud_preview_loading)
        is CloudPreviewState.Failed -> stringResource(R.string.cloud_preview_failed)
        else -> null
    }
    LaunchedEffect(media.messageId, previewState) {
        if (previewState == null) onLoadPreview()
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(spacing.gridItemRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics {
                contentDescription = tileLabel
                statusLabel?.let { stateDescription = it }
            }
            .clickable(onClick = onClick),
    ) {
        when (previewState) {
            is CloudPreviewState.Loaded -> AsyncImage(
                model = File(previewState.localPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            is CloudPreviewState.Loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(26.dp),
                color = MaterialTheme.colorScheme.primary,
            )
            is CloudPreviewState.Failed -> FailedPreviewContent(
                retryable = previewState.retryable,
                onRetry = onRetryPreview,
                modifier = Modifier.align(Alignment.Center),
            )
            // Not yet requested: show the neutral cloud placeholder while the load effect fires.
            null -> Icon(
                imageVector = Icons.Outlined.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
        }

        CloudBadge(modifier = Modifier.align(Alignment.TopEnd).padding(spacing.xs))

        when (media.mediaType) {
            CloudMediaType.VIDEO -> {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(R.string.cloud_media_video),
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(spacing.xs)
                        .size(18.dp),
                )
                DurationLabel.format(media.durationMs)?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(spacing.xs),
                    )
                }
            }
            CloudMediaType.GIF -> Text(
                text = stringResource(R.string.cloud_gif_badge),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(spacing.xs)
                    .background(TelepicTokens.colors.scrim, RoundedCornerShape(4.dp))
                    .padding(horizontal = spacing.xs, vertical = 1.dp),
            )
            CloudMediaType.IMAGE -> Unit
        }
    }
}

@Composable
private fun FailedPreviewContent(
    retryable: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp),
        )
        if (retryable) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.cloud_preview_retry))
            }
        } else {
            Text(
                text = stringResource(R.string.cloud_preview_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CloudBadge(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Outlined.Cloud,
        contentDescription = null,
        tint = Color.White,
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(TelepicTokens.colors.scrim)
            .padding(4.dp),
    )
}
