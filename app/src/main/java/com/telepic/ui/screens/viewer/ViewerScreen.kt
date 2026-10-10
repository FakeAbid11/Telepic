package com.telepic.ui.screens.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.telepic.R
import com.telepic.domain.media.MediaDay
import com.telepic.navigation.MediaSource
import com.telepic.ui.theme.TelepicTokens
import java.io.File

/**
 * The full-screen media Viewer (Phase 9) for both local and cloud items. Photos and GIFs render via
 * Coil with pinch-to-zoom / pan, double-tap-to-reset, and a horizontal swipe that pages to the
 * adjacent item (disabled while zoomed); videos delegate to the isolated Media3 [VideoViewer] and
 * keep button-only navigation. Controls are minimal, the source (Local / Cloud) is labelled, next/
 * previous stay within the originating collection, and a cloud original downloads only on explicit
 * request. A missing item shows an honest error, never another item.
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
    val details by viewModel.details.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }
    var showDetails by remember { mutableStateOf(false) }
    var showTrashConfirm by remember { mutableStateOf(false) }
    val backDesc = stringResource(R.string.viewer_back)
    val isCloudReady = state.source is MediaSource.Cloud && state.status == ViewerStatus.READY
    val isLocalReady = state.source is MediaSource.Local && state.status == ViewerStatus.READY

    // Warm Coil's cache for the swipe targets so a committed drag lands on a ready image.
    val context = LocalContext.current
    LaunchedEffect(state.previous, state.next, state.status) {
        if (state.status != ViewerStatus.READY) return@LaunchedEffect
        listOfNotNull(state.previous, state.next).forEach { neighbor ->
            val model = viewModel.displayModelFor(neighbor)
            if (model != null) {
                context.imageLoader.enqueue(ImageRequest.Builder(context).data(model).build())
            }
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (state.status) {
                ViewerStatus.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                ViewerStatus.MISSING -> MissingMedia(onBack)
                ViewerStatus.READY -> ViewerContent(
                    state = state,
                    onNavigate = viewModel::open,
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
                    onMoveToTrash = { showTrashConfirm = true },
                    onShowDetails = if (isLocalReady) {
                        { viewModel.loadDetails(); showDetails = true }
                    } else null,
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
                AnimatedVisibility(visible = controlsVisible, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = TelepicTokens.spacing.md)) {
                    RestoreAction(onRestore = viewModel::restoreToLocal, restore = restore)
                }
            }

            if (showDetails) {
                MediaDetailsSheet(
                    details = details,
                    onDismiss = { showDetails = false; viewModel.clearDetails() },
                )
            }

            if (showTrashConfirm) {
                AlertDialog(
                    onDismissRequest = { showTrashConfirm = false },
                    title = { Text(stringResource(R.string.viewer_trash_title)) },
                    text = { Text(stringResource(R.string.viewer_trash_body)) },
                    confirmButton = {
                        Button(onClick = {
                            showTrashConfirm = false
                            viewModel.moveToTrash()
                            onBack()
                        }) { Text(stringResource(R.string.viewer_trash_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showTrashConfirm = false }) {
                            Text(stringResource(R.string.organization_cancel))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ViewerContent(
    state: ViewerUiState,
    onNavigate: (MediaSource) -> Unit,
    onDownload: () -> Unit,
    onRequestToggle: () -> Unit,
) {
    if (state.kind == ViewerKind.VIDEO) {
        // Videos keep button navigation only: PlayerView consumes touches for its own controls,
        // and a swipe detector here would fight them.
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
    var swipeOffsetX by remember(state.source) { mutableFloatStateOf(0f) }
    var dragging by remember(state.source) { mutableStateOf(false) }
    // Follows the finger exactly while dragging; springs back to 0 once released without a commit.
    val swipeOffset by animateFloatAsState(
        targetValue = swipeOffsetX,
        animationSpec = if (dragging) snap() else spring(),
        label = "viewerSwipe",
    )
    AsyncImage(
        model = model,
        contentDescription = stringResource(R.string.viewer_content_description),
        contentScale = ContentScale.Fit,
        alignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(state.source) {
                // One gesture pass: two fingers pinch/pan when zoomed, a single-finger horizontal
                // drag swipes to the adjacent item (only while unzoomed). Taps stay on their own
                // detector below — they don't consume drags and vice versa. The @RestrictSuspension
                // gesture scope forbids spawning coroutines, so release feedback is declarative:
                // snap while dragging, spring back through animateFloatAsState when released.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var dragTotalX = 0f
                    var pinched = false
                    var lastPinchDist = 0f
                    var lastCentroid = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            pinched = true
                            val a = pressed[0]
                            val b = pressed[1]
                            val dist = (a.position - b.position).getDistance()
                            val centroid = (a.position + b.position) / 2f
                            if (lastPinchDist > 0f && dist > 0f) {
                                zoom = zoom
                                    .onScale(dist / lastPinchDist)
                                    .onPan(centroid.x - lastCentroid.x, centroid.y - lastCentroid.y)
                            }
                            lastPinchDist = dist
                            lastCentroid = centroid
                            pressed.forEach { it.consume() }
                        } else {
                            val change = pressed.first()
                            val delta = if (change.previousPosition != Offset.Unspecified) {
                                change.position - change.previousPosition
                            } else {
                                Offset.Zero
                            }
                            if (zoom.isZoomed) {
                                zoom = zoom.onPan(delta.x, delta.y)
                                change.consume()
                            } else if (!pinched) {
                                dragging = true
                                dragTotalX += delta.x
                                swipeOffsetX = dragTotalX
                                change.consume()
                            }
                        }
                    }
                    if (!pinched && !zoom.isZoomed && dragTotalX != 0f) {
                        val target = SwipeResolver.resolve(
                            deltaPx = dragTotalX,
                            widthPx = size.width.toFloat(),
                            previous = state.previous,
                            next = state.next,
                        )
                        if (target != null) {
                            // Navigating swaps the remember(state.source) offset anyway; the swap is
                            // immediate, identical to the arrow buttons' path through ViewModel.open.
                            swipeOffsetX = 0f
                            onNavigate(target)
                        }
                    }
                    dragging = false
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
                translationX = zoom.offsetX + swipeOffset,
                translationY = zoom.offsetY,
            ),
    )
}

@Composable
internal fun CloudUnavailable(state: ViewerUiState, onDownload: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TelepicTokens.spacing.md, Alignment.CenterVertically),
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
    onShowDetails: (() -> Unit)? = null,
) {
    val spacing = TelepicTokens.spacing
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
            onShowDetails?.let { showDetails ->
                IconButton(onClick = showDetails) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.viewer_details),
                        tint = Color.White,
                    )
                }
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
    val spacing = TelepicTokens.spacing
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
            modifier = Modifier.padding(horizontal = TelepicTokens.spacing.sm),
        )
        is RestoreState.Failed -> Text(
            text = stringResource(R.string.viewer_save_failed),
            color = Color(0xFFFF8A80),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = TelepicTokens.spacing.xs),
        )
        RestoreState.Idle -> TextButton(onClick = onRestore) {
            Icon(Icons.Outlined.SaveAlt, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.viewer_save_device), color = Color.White, modifier = Modifier.padding(start = TelepicTokens.spacing.xs))
        }
    }
}

@Composable
private fun MissingMedia(onBack: () -> Unit) {
    val spacing = TelepicTokens.spacing
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
