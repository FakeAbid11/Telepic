package com.telepix.ui.screens.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.telepix.R
import com.telepix.domain.media.MediaDay
import com.telepix.navigation.MediaSource
import com.telepix.ui.theme.TelepixTokens
import java.io.File

/**
 * The full-screen media Viewer (Phase 9) for both local and cloud items. Photos and GIFs render via
 * Coil with pinch-to-zoom / pan and double-tap-to-reset; videos delegate to the isolated Media3
 * [VideoViewer]. Controls are minimal, the source (Local / Cloud) is labelled, next/previous stay
 * within the originating collection, and a cloud original downloads only on explicit request. A
 * missing item shows an honest error, never another item.
 */
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val flags by viewModel.flags.collectAsStateWithLifecycle()
    val restore by viewModel.restore.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }
    val backDesc = stringResource(R.string.viewer_back)
    val isCloudReady = state.source is MediaSource.Cloud && state.status == ViewerStatus.READY

    Surface(modifier = modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (state.status) {
                ViewerStatus.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                ViewerStatus.MISSING -> MissingMedia(onBack)
                ViewerStatus.READY -> ViewerContent(
                    state = state,
                    onDownload = viewModel::downloadOriginal,
                    onRequestToggle = { controlsVisible = !controlsVisible },
                )
            }

            AnimatedVisibility(visible = controlsVisible, modifier = Modifier.align(Alignment.TopCenter)) {
                ViewerTopBar(
                    state = state,
                    flags = flags,
                    onBack = onBack,
                    backDesc = backDesc,
                    showActions = state.source is MediaSource.Local && state.status == ViewerStatus.READY,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onToggleArchive = { viewModel.toggleArchive(); onBack() },
                    onMoveToTrash = { viewModel.moveToTrash(); onBack() },
                )
            }

            if (state.status == ViewerStatus.READY && (state.previous != null || state.next != null)) {
                AnimatedVisibility(visible = controlsVisible, modifier = Modifier.align(Alignment.BottomCenter)) {
                    ViewerBottomBar(
                        state = state,
                        onPrevious = viewModel::showPrevious,
                        onNext = viewModel::showNext,
                        onDownload = viewModel::downloadOriginal,
                        onRestore = if (isCloudReady) viewModel::restoreToLocal else null,
                        restore = restore,
                    )
                }
            } else if (isCloudReady) {
                // A lone cloud item has no nav bar; offer Save-to-device on its own.
                AnimatedVisibility(visible = controlsVisible, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = TelepixTokens.spacing.md)) {
                    RestoreAction(onRestore = viewModel::restoreToLocal, restore = restore)
                }
            }
        }
    }
}

@Composable
private fun ViewerContent(
    state: ViewerUiState,
    onDownload: () -> Unit,
    onRequestToggle: () -> Unit,
) {
    if (state.kind == ViewerKind.VIDEO) {
        VideoViewer(state = state, onDownload = onDownload)
        return
    }

    val model: Any? = when (val item = state.item) {
        is ViewerItem.Local -> item.media.contentUri
        is ViewerItem.Cloud -> item.originalPath?.let { File(it) } ?: item.previewPath?.let { File(it) }
        null -> null
    }

    if (model == null) {
        CloudUnavailable(state = state, onDownload = onDownload)
        return
    }

    var zoom by remember(state.source) { mutableStateOf(ZoomState()) }
    AsyncImage(
        model = model,
        contentDescription = stringResource(R.string.viewer_content_description),
        contentScale = ContentScale.Fit,
        alignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(state.source) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    zoom = zoom.onScale(gestureZoom).onPan(pan.x, pan.y)
                }
            }
            .pointerInput(state.source) {
                detectTapGestures(
                    onTap = { onRequestToggle() },
                    onDoubleTap = { zoom = zoom.reset() },
                )
            }
            .graphicsLayer(
                scaleX = zoom.scale,
                scaleY = zoom.scale,
                translationX = zoom.offsetX,
                translationY = zoom.offsetY,
            ),
    )
}

@Composable
internal fun CloudUnavailable(state: ViewerUiState, onDownload: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TelepixTokens.spacing.md, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudDownload,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = stringResource(
                if (state.download is CloudDownload.Unavailable) R.string.viewer_download_unavailable
                else R.string.viewer_download_required,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
        if (state.download == CloudDownload.Downloading) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
        } else {
            TextButton(onClick = onDownload) { Text(stringResource(R.string.viewer_download), color = Color.White) }
        }
    }
}

@Composable
private fun ViewerTopBar(
    state: ViewerUiState,
    flags: ViewerFlags,
    onBack: () -> Unit,
    backDesc: String,
    showActions: Boolean,
    onToggleFavorite: () -> Unit,
    onToggleArchive: () -> Unit,
    onMoveToTrash: () -> Unit,
) {
    val spacing = TelepixTokens.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x88000000))
            .padding(horizontal = spacing.xs, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backDesc, tint = Color.White)
        }
        Column(modifier = Modifier.padding(start = spacing.xs).weight(1f)) {
            state.dateMillis?.let {
                Text(text = MediaDay.label(it), style = MaterialTheme.typography.titleSmall, color = Color.White)
            }
            Text(
                text = stringResource(
                    if (state.source is MediaSource.Cloud) R.string.viewer_source_cloud
                    else R.string.viewer_source_local,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        if (showActions) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (flags.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(
                        if (flags.isFavorite) R.string.viewer_unfavorite else R.string.viewer_favorite,
                    ),
                    tint = if (flags.isFavorite) Color(0xFFFFC53D) else Color.White,
                )
            }
            IconButton(onClick = onToggleArchive) {
                Icon(
                    imageVector = Icons.Outlined.Archive,
                    contentDescription = stringResource(
                        if (flags.isArchived) R.string.viewer_unarchive else R.string.viewer_archive,
                    ),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onMoveToTrash) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.viewer_move_to_trash),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ViewerBottomBar(
    state: ViewerUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDownload: () -> Unit,
    onRestore: (() -> Unit)? = null,
    restore: RestoreState = RestoreState.Idle,
) {
    val spacing = TelepixTokens.spacing
    val showDownload = state.source is MediaSource.Cloud && state.download !is CloudDownload.Available
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x88000000))
            .padding(vertical = spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, enabled = state.previous != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.viewer_previous),
                tint = if (state.previous != null) Color.White else Color.White.copy(alpha = 0.3f),
            )
        }
        when {
            onRestore != null -> RestoreAction(onRestore = onRestore, restore = restore)
            showDownload -> {
                if (state.download == CloudDownload.Downloading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp))
                } else {
                    TextButton(onClick = onDownload) {
                        Icon(Icons.Outlined.CloudDownload, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.viewer_download), color = Color.White, modifier = Modifier.padding(start = spacing.xs))
                    }
                }
            }
            else -> Box(modifier = Modifier.size(48.dp))
        }
        IconButton(onClick = onNext, enabled = state.next != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.viewer_next),
                tint = if (state.next != null) Color.White else Color.White.copy(alpha = 0.3f),
            )
        }
    }
}

@Composable
private fun RestoreAction(onRestore: () -> Unit, restore: RestoreState) {
    when (restore) {
        RestoreState.Restoring -> CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp))
        is RestoreState.Restored -> Text(
            text = stringResource(R.string.viewer_saved),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = TelepixTokens.spacing.sm),
        )
        is RestoreState.Failed -> Text(
            text = stringResource(R.string.viewer_save_failed),
            color = Color(0xFFFF8A80),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = TelepixTokens.spacing.xs),
        )
        RestoreState.Idle -> TextButton(onClick = onRestore) {
            Icon(Icons.Outlined.SaveAlt, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.viewer_save_device), color = Color.White, modifier = Modifier.padding(start = TelepixTokens.spacing.xs))
        }
    }
}

@Composable
private fun MissingMedia(onBack: () -> Unit) {
    val spacing = TelepixTokens.spacing
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.md, Alignment.CenterVertically),
    ) {
        Icon(Icons.Outlined.PhotoLibrary, null, tint = Color.White, modifier = Modifier.size(40.dp))
        Text(stringResource(R.string.viewer_missing), style = MaterialTheme.typography.bodyLarge, color = Color.White)
        TextButton(onClick = onBack) { Text(stringResource(R.string.viewer_back), color = Color.White) }
    }
}
