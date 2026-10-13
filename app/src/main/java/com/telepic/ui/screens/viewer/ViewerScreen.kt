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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.telepic.R
import com.telepic.data.restore.RestoreFailure
import com.telepic.domain.media.MediaDay
import com.telepic.navigation.MediaSource
import com.telepic.ui.components.TransientMessageBar
import com.telepic.ui.components.rememberSharedMediaModifier
import com.telepic.ui.components.rememberTelepicHaptics
import com.telepic.ui.theme.TelepicTokens
import java.io.File

/**
 * The Viewer's two chrome bars. Tagged so a test can pin their measured heights against
 * `TelepicSpacing.chromeBarHeight` — the value the video surface reserves for them.
 */
internal const val TAG_VIEWER_TOP_BAR = "viewer_top_bar"
internal const val TAG_VIEWER_BOTTOM_BAR = "viewer_bottom_bar"

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
    onShowOnMap: ((latitude: Double, longitude: Double) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val flags by viewModel.flags.collectAsStateWithLifecycle()
    val restore by viewModel.restore.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val startVideosMuted by viewModel.startVideosMuted.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    // Rotation-surviving chrome state: controls, sheets and the pending confirm dialog should not
    // reset just because the Activity was recreated.
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showTrashConfirm by rememberSaveable { mutableStateOf(false) }
    val backDesc = stringResource(R.string.viewer_back)
    val isCloudReady = state.source is MediaSource.Cloud && state.status == ViewerStatus.READY
    val haptics = rememberTelepicHaptics()

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

    // The Details sheet survives process death via rememberSaveable; if it re-opens with no
    // details loaded (restored instance, or a swipe to a new item while the sheet was open),
    // load them instead of spinning forever. Applies to cloud items too — their details come from
    // the manifest entry, so the sheet is no longer a dead end there.
    LaunchedEffect(showDetails, state.source) {
        if (showDetails && state.status == ViewerStatus.READY && details == null) {
            viewModel.loadDetails()
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = TelepicTokens.colors.viewerBackdrop) {
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
                    // Handed down for the video transport, which *is* the bottom bar for a playable
                    // video (see ViewerUiState.isPlayableVideo).
                    onPrevious = viewModel::showPrevious,
                    onNext = viewModel::showNext,
                    onRestore = if (isCloudReady) viewModel::restoreToLocal else null,
                    restore = restore,
                    // "Start videos muted" is the *initial* state for each clip, not a lock: the
                    // user's own unmute for the video they are watching is never overridden.
                    startMuted = startVideosMuted,
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
                    // Flag-only, like favorite: toggling archive never kicks the user out of the
                    // Viewer. Grids behind react on their own when the Room flag flow re-emits.
                    onToggleArchive = viewModel::toggleArchive,
                    onMoveToTrash = { showTrashConfirm = true },
                    // Offered for cloud items as well as local ones: a cloud item's details come from
                    // its manifest entry, so there is real information to show rather than a spinner.
                    onShowDetails = if (state.status == ViewerStatus.READY) {
                        { viewModel.loadDetails(); showDetails = true }
                    } else null,
                )
            }

            // A playable video draws its own transport bar (play/pause, seek, prev/next and the cloud
            // save action) in the band the surface reserves, so the generic bar is skipped: rendering
            // both is precisely the two-stacked-bars defect the transport replaced.
            if (state.status == ViewerStatus.READY &&
                !state.isPlayableVideo &&
                (state.previous != null || state.next != null)
            ) {
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
            } else if (isCloudReady && !state.isPlayableVideo) {
                // A lone cloud item has no nav bar; offer Save-to-device on its own.
                AnimatedVisibility(
                    visible = controlsVisible,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = TelepicTokens.spacing.md),
                ) {
                    RestoreAction(onRestore = viewModel::restoreToLocal, restore = restore)
                }
            }

            if (showDetails) {
                MediaDetailsSheet(
                    details = details,
                    onDismiss = { showDetails = false; viewModel.clearDetails() },
                    // Close the sheet before handing off: returning from the map should land on the
                    // photo, not on a sheet the user had already acted on.
                    onShowOnMap = onShowOnMap?.let { show ->
                        { latitude: Double, longitude: Double ->
                            showDetails = false
                            viewModel.clearDetails()
                            show(latitude, longitude)
                        }
                    },
                )
            }

            // Trashing leaves no other trace on screen — the Viewer deliberately stays on the item
            // and it keeps rendering unchanged — so this bar is the only thing that makes the action
            // observable. It offers Undo because Trash is not reachable from the Viewer.
            message?.let { pending ->
                TransientMessageBar(
                    message = stringResource(pending.messageRes),
                    onDismiss = viewModel::consumeMessage,
                    // Keyed on the item, so the countdown belongs to this item's event and does not
                    // inherit a nearly-expired one from a previous action.
                    messageId = state.source,
                    actionLabel = stringResource(R.string.viewer_trashed_undo),
                    onAction = viewModel::restoreFromTrash,
                    // Clear of the bottom chrome rather than on top of it: this bar is composed
                    // after the bars, so it would otherwise cover the navigation row.
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = TelepicTokens.spacing.chromeBarHeight),
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
                            haptics.confirmDestructive()
                            // Stay put: the write is the VM's, and the flag flow reflects it. Popping
                            // before the async Room write used to hide failures and yank the user back.
                            viewModel.moveToTrash()
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
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onRestore: (() -> Unit)? = null,
    restore: RestoreState = RestoreState.Idle,
    startMuted: Boolean = true,
) {
    if (state.kind == ViewerKind.VIDEO) {
        // Videos keep button navigation only: the surface consumes touches for its own controls,
        // and a swipe detector here would fight them.
        VideoViewer(
            state = state,
            onDownload = onDownload,
            onPrevious = onPrevious,
            onNext = onNext,
            onRestore = onRestore,
            restore = restore,
            startMuted = startMuted,
        )
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

    // The *target* end of the shared-element transition, keyed on the same MediaStore id the tile the
    // user tapped used. Null for a cloud item — it has no tile in a local grid, so there is nothing
    // for it to grow out of and the Viewer simply appears (see rememberSharedMediaModifier).
    val sharedImageModifier = rememberSharedMediaModifier((state.item as? ViewerItem.Local)?.media?.id)

    var zoom by remember(state.source) { mutableStateOf(ZoomState()) }
    var swipeOffsetX by remember(state.source) { mutableFloatStateOf(0f) }
    var dragging by remember(state.source) { mutableStateOf(false) }
    // Follows the finger exactly while dragging; springs back to 0 once released without a commit.
    val swipeOffset by animateFloatAsState(
        targetValue = swipeOffsetX,
        animationSpec = if (dragging) snap() else spring(),
        label = "viewerSwipe",
    )
    // Coil's real load state, not an assumption. The indicator used to be composed
    // unconditionally behind the image, so a failed load spun forever — telling the user
    // "still working" about work that had already stopped — and a success kept an invisible
    // animation alive for the life of the screen.
    var loadState by remember(model) { mutableStateOf(ImageLoadState.Loading) }
    var retryToken by remember(model) { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Shown behind the image while a (possibly slow) cloud preview or local decode loads —
        // the letterboxed backdrop otherwise read as a dead black screen.
        if (loadState == ImageLoadState.Loading) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        // Keyed on the retry token: bumping it discards the failed painter and builds a fresh
        // one, which re-issues the request. Coil does not cache failures, so the retry is real.
        key(retryToken) {
            AsyncImage(
                model = model,
                contentDescription = stringResource(R.string.viewer_content_description),
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center,
                // The one line that turns the spinner below into a truthful indicator.
                onState = { loadState = it.toImageLoadState() },
                modifier = Modifier
                    .fillMaxSize()
                    .then(sharedImageModifier)
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
                                    val scaled = zoom.onScale(dist / lastPinchDist)
                                    // Re-clamp the pan against the NEW scale so pinch-out can't leave
                                    // the image stranded off-screen.
                                    zoom = scaled.onPan(
                                        centroid.x - lastCentroid.x,
                                        centroid.y - lastCentroid.y,
                                        boundX = (scaled.scale - 1f) * size.width / 2f,
                                        boundY = (scaled.scale - 1f) * size.height / 2f,
                                    )
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
                                    // Bound by the viewport half-extent scaled past fit. Deliberate
                                    // approximation: the true limit is the image's painted size, which
                                    // differs per axis under ContentScale.Fit — viewport bounds still
                                    // make "drag it permanently away" impossible without painter metrics.
                                    zoom = zoom.onPan(
                                        delta.x,
                                        delta.y,
                                        boundX = (zoom.scale - 1f) * size.width / 2f,
                                        boundY = (zoom.scale - 1f) * size.height / 2f,
                                    )
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
                            // Always release the drag offset: a committed swap navigates anyway (and
                            // swaps the remembered offset), while a rejected or under-threshold drag
                            // springs back to rest instead of freezing the image sideways.
                            swipeOffsetX = 0f
                            if (target != null) {
                                // Navigating swaps the remember(state.source) offset anyway; the swap is
                                // immediate, identical to the arrow buttons' path through ViewModel.open.
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
        // Drawn after (i.e. above) the image so its button is hit-tested first: the image's
        // own gesture layer consumes taps and would otherwise swallow the retry press.
        if (loadState == ImageLoadState.Error) {
            ViewerImageError(onRetry = { retryToken++ })
        }
    }
}

/**
 * The three Coil painter states the Viewer actually reacts to. [AsyncImagePainter.State.Empty]
 * collapses into [Loading]: a request that has not started yet is, from the user's seat, still
 * loading.
 */
private enum class ImageLoadState { Loading, Success, Error }

private fun AsyncImagePainter.State.toImageLoadState(): ImageLoadState = when (this) {
    is AsyncImagePainter.State.Success -> ImageLoadState.Success
    is AsyncImagePainter.State.Error -> ImageLoadState.Error
    else -> ImageLoadState.Loading
}

/**
 * Shown when Coil reports a genuine read/decode failure, instead of a spinner that never stops.
 * Styled like [CloudUnavailable] (white on the dark media backdrop) rather than reusing
 * `ErrorState`: that component paints `onSurface`, which is near-black in the light theme and
 * would be unreadable against this backdrop.
 */
@Composable
private fun ViewerImageError(onRetry: () -> Unit) {
    val spacing = TelepicTokens.spacing
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.md, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = Icons.Outlined.BrokenImage,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = stringResource(R.string.viewer_image_error),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) {
            Text(text = stringResource(R.string.common_retry), color = Color.White)
        }
    }
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
    val chromeScrim = TelepicTokens.colors.chromeScrim
    // Read once, not per recomposition: the header must not flip between the compact and the full
    // date form while the screen is open.
    val nowMillis = remember { System.currentTimeMillis() }
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_VIEWER_TOP_BAR)
            // Background *before* the status-bar inset, so the scrim also covers the strip behind
            // the system icons instead of stopping short and leaving the clock on bare photo.
            // The gradient holds the scrim across the band the text occupies and only fades over the
            // row's own bottom padding — a flat fill left a hard horizontal edge across the photo,
            // while fading under the text would drop the source label below AA contrast.
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to chromeScrim,
                        0.88f to chromeScrim,
                        1f to Color.Transparent,
                    ),
                ),
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = spacing.xs, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backDesc, tint = Color.White)
        }
        Column(modifier = Modifier.padding(start = spacing.xs).weight(1f)) {
            state.dateMillis?.let { dateMillis ->
                Text(
                    // Month-and-day within the current year, the full date otherwise — the year of an
                    // older photo must not be silently dropped just to make the header fit.
                    text = MediaDay.monthDayOrFullDate(dateMillis, nowMillis),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    // Without this the date wraps on a 360dp phone and grows the bar from 48dp to
                    // roughly 68dp, shoving the photo down; ellipsis degrades instead of reflowing.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                text = stringResource(
                    if (state.source is MediaSource.Cloud) R.string.viewer_source_cloud
                    else R.string.viewer_source_local,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showActions) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (flags.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(
                        if (flags.isFavorite) R.string.viewer_unfavorite else R.string.viewer_favorite,
                    ),
                    tint = if (flags.isFavorite) TelepicTokens.colors.favoriteAmber else Color.White,
                )
            }
            // Archive, trash and details sit behind one overflow control. As four separate 48dp
            // buttons they left the date column only 108dp — less than a long month name needs, so
            // the title wrapped. Favourite stays inline: it is a toggle the user reads at a glance.
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.viewer_more_actions),
                        tint = Color.White,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (flags.isArchived) R.string.viewer_unarchive
                                    else R.string.viewer_archive,
                                ),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onToggleArchive()
                        },
                        leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_move_to_trash)) },
                        onClick = {
                            menuOpen = false
                            onMoveToTrash()
                        },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    )
                    onShowDetails?.let { showDetails ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_details)) },
                            onClick = {
                                menuOpen = false
                                showDetails()
                            },
                            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                        )
                    }
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
    val chromeScrim = TelepicTokens.colors.chromeScrim
    val showDownload = state.source is MediaSource.Cloud && state.download !is CloudDownload.Available
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_VIEWER_BOTTOM_BAR)
            // Same treatment as the top bar: the background precedes the nav-bar inset so the scrim
            // also covers the strip behind the system bar, and the fade lives in the empty padding
            // above the controls rather than underneath them.
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.30f to chromeScrim,
                        1f to chromeScrim,
                    ),
                ),
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
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

/**
 * Human-readable message for a restore failure. Every reason the repository can report has its own
 * wording, so the user learns *why* a save failed instead of a single generic message. Internal,
 * because the video transport renders the same reasons beside its save action.
 */
internal val RestoreFailure.messageRes: Int
    get() = when (this) {
        RestoreFailure.DOWNLOAD_UNAVAILABLE -> R.string.viewer_save_failed_download
        RestoreFailure.MISSING_REMOTE -> R.string.viewer_save_failed_missing
        RestoreFailure.VERIFY_FAILED -> R.string.viewer_save_failed_verify
        RestoreFailure.LOW_STORAGE -> R.string.viewer_save_failed_storage
        RestoreFailure.PUBLISH_FAILED -> R.string.viewer_save_failed
    }

/** The sentence a [ViewerMessage] is reported with. Stated as the outcome, not the intent. */
private val ViewerMessage.messageRes: Int
    get() = when (this) {
        ViewerMessage.MovedToTrash -> R.string.viewer_trashed_message
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
            text = stringResource(restore.reason.messageRes),
            color = TelepicTokens.colors.mediaErrorText,
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
