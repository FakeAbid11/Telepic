package com.telepix.ui.screens.cloud

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.telepix.R
import com.telepix.domain.cloud.CloudMedia
import com.telepix.domain.cloud.CloudMediaType
import com.telepix.domain.cloud.CloudStatus
import com.telepix.domain.cloud.CloudUiState
import com.telepix.ui.components.EmptyState
import com.telepix.ui.components.ErrorState
import com.telepix.ui.components.LoadingState
import com.telepix.ui.components.ScreenHeader
import com.telepix.ui.components.StateAction
import com.telepix.ui.theme.TelepixTokens
import java.io.File
import java.util.Locale

/** Real Telegram cloud screen (Phase 5). Browsing is preview-only; originals are never bulk-downloaded. */
@Composable
fun CloudScreen(
    viewModel: CloudViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val previews by viewModel.previews.collectAsStateWithLifecycle()
    val spacing = TelepixTokens.spacing

    Surface(modifier = modifier.fillMaxSize(), color = TelepixTokens.colors.mediaBackdrop) {
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
                    onOpen = viewModel::downloadOriginal,
                    onRetry = viewModel::refresh,
                )
            }
        }
    }
}

@Composable
private fun DestinationStatusBar(uiState: CloudUiState) {
    val spacing = TelepixTokens.spacing
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
    previews: Map<Long, String>,
    onLoadPreview: (CloudMedia) -> Unit,
    onOpen: (CloudMedia) -> Unit,
    onRetry: () -> Unit,
) {
    when (uiState.status) {
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
            description = (uiState.status as CloudStatus.DestinationInvalid).reason,
            primaryAction = StateAction(stringResource(R.string.cloud_retry), onRetry),
        )
        is CloudStatus.Failed -> ErrorState(
            title = stringResource(R.string.cloud_error_title),
            explanation = (uiState.status as CloudStatus.Failed).message.ifBlank {
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
            CloudGrid(uiState.media, previews, onLoadPreview, onOpen)
        } else {
            LoadingState()
        }
        CloudStatus.Ready -> CloudGrid(uiState.media, previews, onLoadPreview, onOpen)
    }
}

@Composable
private fun CloudGrid(
    media: List<CloudMedia>,
    previews: Map<Long, String>,
    onLoadPreview: (CloudMedia) -> Unit,
    onOpen: (CloudMedia) -> Unit,
) {
    val spacing = TelepixTokens.spacing
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(spacing.gridGutter),
        horizontalArrangement = Arrangement.spacedBy(spacing.gridGutter),
        verticalArrangement = Arrangement.spacedBy(spacing.gridGutter),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(media, key = { "${it.chatId}_${it.messageId}" }) { item ->
            CloudTile(
                media = item,
                previewPath = previews[item.messageId],
                onLoadPreview = { onLoadPreview(item) },
                onClick = { onOpen(item) },
            )
        }
    }
}

@Composable
private fun CloudTile(
    media: CloudMedia,
    previewPath: String?,
    onLoadPreview: () -> Unit,
    onClick: () -> Unit,
) {
    val spacing = TelepixTokens.spacing
    LaunchedEffect(media.messageId, previewPath) {
        if (previewPath == null) onLoadPreview()
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(spacing.gridItemRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        if (previewPath != null) {
            AsyncImage(
                model = File(previewPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp),
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
                media.durationMs?.let {
                    Text(
                        text = formatDuration(it),
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
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = spacing.xs, vertical = 1.dp),
            )
            CloudMediaType.IMAGE -> Unit
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
            .background(Color(0x80000000))
            .padding(4.dp),
    )
}

// These two small helpers keep the retry callbacks wired to a refresh without threading the
// ViewModel into every leaf composable.
private fun formatDuration(millis: Long): String {
    if (millis <= 0L) return ""
    val total = millis / 1000
    val m = total / 60
    val s = total % 60
    return String.format(Locale.US, "%d:%02d", m, s)
}
