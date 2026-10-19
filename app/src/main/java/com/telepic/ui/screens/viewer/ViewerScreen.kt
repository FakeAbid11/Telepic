package com.telepic.ui.screens.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.telepic.R
import com.telepic.data.restore.RestoreFailure
import com.telepic.domain.backup.MediaBackupVisualState
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
 * Coil with pinch-to-zoom anchored under the fingers, pan, double-tap to magnify at the tapped point
 * (and again to return), and a horizontal swipe that pages to the adjacent item (disabled while
 * zoomed); videos delegate to the isolated Media3 [VideoViewer], which adds the player gestures
 * (double-tap seek, vertical brightness/volume, long-press speed) on a full-bleed surface.
 * The display stays awake while a Viewer is on screen and the system bars go with the chrome.
 * Controls are minimal, the source (Local / Cloud) is labelled, next/
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
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val backupBusy by viewModel.backupBusy.collectAsStateWithLifecycle()
    // Rotation-surviving chrome state: controls, sheets and the pending confirm dialog should not
    // reset just because the Activity was recreated.
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showTrashConfirm by rememberSaveable { mutableStateOf(false) }
    val backDesc = stringResource(R.string.viewer_back)
    val isCloudReady = state.source is MediaSource.Cloud && state.status == ViewerStatus.READY
    val haptics = rememberTelepicHaptics()

    // A viewer is looked at, not left idle. Without this the display dims and sleeps mid-video, or
    // halfway through a slow read of a full-screen photo, and a paused clip behind a black screen is
    // indistinguishable from a crash. Cleared on the way out so the device keeps its own timeout.
    val viewerView = LocalView.current
    DisposableEffect(Unit) {
        viewerView.setKeepScreenOn(true)
        onDispose { viewerView.setKeepScreenOn(false) }
    }

    // The app's own chrome hiding is not the whole job: on an edge-to-edge window the status and
    // navigation bars stay painted over the media, so "immersive" was two dark strips plus a dimmed
    // screen. They go away with the chrome and come back on the way out — a viewer that left the
    // system bars hidden after unmounting would be worse than one that never hid them.
    // `WindowInsetsControllerCompat` has no View-only constructor, so the window comes from the hosting
    // Activity. Null only when there is no Activity to attach to — a Compose preview — and then the
    // composition keeps the bars it already has rather than guessing at a window.
    val insetsController = remember(viewerView) {
        viewerView.context.hostingWindow()?.let { WindowInsetsControllerCompat(it, viewerView) }
    }
    // Two effects, not one keyed on `controlsVisible`.
    //
    // A single effect re-ran on every chrome toggle, and `onDispose` runs *before* the new effect
    // body: hiding the chrome therefore showed the system bars and hid them again, so the status
    // and navigation bars flashed into view for a frame on every tap. The restore-on-exit belongs
    // to the unmount, not to the toggle.
    DisposableEffect(insetsController) {
        val controller = insetsController ?: return@DisposableEffect onDispose { }
        val bars = WindowInsetsCompat.Type.systemBars()
        // Restored on the way out, so no other screen ever inherits a hidden status bar.
        onDispose { controller.show(bars) }
    }
    LaunchedEffect(insetsController, controlsVisible) {
        val controller = insetsController ?: return@LaunchedEffect
        val bars = WindowInsetsCompat.Type.systemBars()
        if (controlsVisible) {
            controller.show(bars)
        } else {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(bars)
        }
    }

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
    //
    // `state.status` is a key because the restore case composes while the item is still LOADING:
    // without it the one and only run would be rejected by the READY guard below and, since neither
    // `showDetails` nor `state.source` changed afterwards, the effect would never re-run — leaving the
    // sheet on its spinner for good. Keying on status re-runs it the moment the item becomes READY.
    LaunchedEffect(showDetails, state.source, state.status) {
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
                    onSetControlsVisible = { controlsVisible = it },
                    controlsVisible = controlsVisible,
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
                    backupState = backupState,
                    backupBusy = backupBusy,
                    onBackup = viewModel::backupCurrent,
                    // Offered for cloud items as well as local ones: a cloud item's details come from
                    // its manifest entry, so there is real information to show rather than a spinner.
                    // Opening the sheet is enough — the LaunchedEffect above owns the load, so this does
                    // not also kick one off and read the metadata twice.
                    onShowDetails = if (state.status == ViewerStatus.READY) {
                        { showDetails = true }
                    } else null,
                )
            }

            // A playable video draws its own transport bar (play/pause, seek and the cloud save
            // action) in the band the surface reserves, so the generic bar is skipped: rendering
            // both is precisely the two-stacked-bars defect the transport replaced.
            //
            // The generic bar only carries the cloud save/download actions now that the arrows are
            // gone, so a local photo renders no bottom bar at all rather than an empty scrim.
            if (isCloudReady && !state.isPlayableVideo) {
                AnimatedVisibility(visible = controlsVisible, modifier = Modifier.align(Alignment.BottomCenter)) {
                    ViewerBottomBar(
                        state = state,
                        onDownload = viewModel::downloadOriginal,
                        onRestore = viewModel::restoreToLocal,
                        restore = restore,
                    )
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
                // Only the trash outcome is reversible. A backup report has nothing to undo, and
                // offering "Undo" beside it would wire that button to `restoreFromTrash` — un-trashing
                // an item that was never trashed.
                val undoable = pending is ViewerMessage.MovedToTrash
                TransientMessageBar(
                    message = stringResource(pending.messageRes),
                    onDismiss = viewModel::consumeMessage,
                    // Keyed on the item, so the countdown belongs to this item's event and does not
                    // inherit a nearly-expired one from a previous action.
                    messageId = state.source,
                    actionLabel = if (undoable) stringResource(R.string.viewer_trashed_undo) else null,
                    onAction = if (undoable) viewModel::restoreFromTrash else null,
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
    onSetControlsVisible: (Boolean) -> Unit,
    controlsVisible: Boolean = true,
    onRestore: (() -> Unit)? = null,
    restore: RestoreState = RestoreState.Idle,
    startMuted: Boolean = true,
) {
    if (state.kind == ViewerKind.VIDEO) {
        VideoViewer(
            state = state,
            onDownload = onDownload,
            onNavigate = onNavigate,
            onRestore = onRestore,
            restore = restore,
            startMuted = startMuted,
            controlsVisible = controlsVisible,
            onSetControlsVisible = onSetControlsVisible,
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

    // Name the item specifically so a screen-reader user knows which photo they are viewing,
    // rather than hearing the generic "Media viewer image" on every single one.
    val itemName = when (val item = state.item) {
        is ViewerItem.Local -> item.media.displayName
        is ViewerItem.Cloud -> item.media.fileName
        null -> null
    }
    val imageDescription = itemName
        ?: stringResource(R.string.viewer_content_description)

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

    // The swipe targets are read through `rememberUpdatedState` so the gesture block — which is keyed
    // on the *source* and therefore not restarted when the neighbours change — always resolves the
    // current pair instead of the one captured when the block was created. The video path already
    // does this; the photo path did not, so a neighbour change under the same item could swipe to
    // an item that was no longer adjacent.
    val currentPrevious by rememberUpdatedState(state.previous)
    val currentNext by rememberUpdatedState(state.next)

    // Back is not simply "leave": while the photo is zoomed the first press returns it to fit, and
    // while the chrome is hidden the first press brings it back — the same order the user expects from
    // a full-screen image. Only when neither applies does Back fall through to the route pop that
    // `onBack` performs. The sheet and the trash dialog are separate windows and consume Back first,
    // so this never fires underneath them.
    BackHandler(enabled = zoom.isZoomed || !controlsVisible) {
        if (zoom.isZoomed) {
            zoom = ZoomState()
        } else {
            onRequestToggle()
        }
    }

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
                contentDescription = imageDescription,
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
                                    // Scale about the fingers, then carry the centroid's own movement.
                                    // Scaling via onScale instead anchors the zoom on the viewport centre,
                                    // so the patch being pinched slides away from the fingers mid-gesture.
                                    val scaled = zoom.onScaleAt(
                                        factor = dist / lastPinchDist,
                                        pivotX = centroid.x,
                                        pivotY = centroid.y,
                                        centerX = size.width / 2f,
                                        centerY = size.height / 2f,
                                    )
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
                                // Read through `rememberUpdatedState`: the gesture block is keyed on
                                // the *source*, so it is not restarted when the neighbour set changes
                                // (a MediaStore refresh, a scoped neighbour query re-resolving). Reading
                                // `state.previous` directly would capture it by value and swipe to a
                                // neighbour that is no longer there.
                                previous = currentPrevious,
                                next = currentNext,
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
                        // Magnifies at the tapped point, and taps out again. It used to be
                        // `zoom.reset()` alone, which meant the expected gesture did nothing at all
                        // until the user had already pinched in — where it then undid their zoom.
                        onDoubleTap = { point ->
                            zoom = zoom.toggleAt(
                                pivotX = point.x,
                                pivotY = point.y,
                                centerX = size.width / 2f,
                                centerY = size.height / 2f,
                            )
                        },
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
        GlassMedallion(imageVector = Icons.Outlined.BrokenImage)
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
        GlassMedallion(imageVector = Icons.Outlined.CloudDownload)
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
    backupState: MediaBackupVisualState = MediaBackupVisualState.NONE,
    backupBusy: Boolean = false,
    onBackup: () -> Unit = {},
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
            .padding(horizontal = spacing.sm, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Glass chips rather than bare glyphs: the chrome reads as a designed overlay with a clear
        // edge over a busy photo, and the touch target is unchanged.
        GlassIconButton(
            onClick = onBack,
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = backDesc,
        )
        Column(modifier = Modifier.padding(start = spacing.md).weight(1f)) {
            state.dateMillis?.let { dateMillis ->
                Text(
                    // Month-and-day within the current year, the full date otherwise — the year of an
                    // older photo must not be silently dropped just to make the header fit.
                    text = MediaDay.monthDayOrFullDate(dateMillis, nowMillis),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    // Without this the date wraps on a 360dp phone and grows the bar from 48dp to
                    // roughly 68dp, shoving the photo down; ellipsis degrades instead of reflowing.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                // A small source dot sets "where this came from" apart from the date line without a
                // second full-width row.
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color.White.copy(alpha = 0.7f), CircleShape),
                )
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
        }
        if (showActions) {
            // Favourite, backup and the overflow share one glass pill, so the action cluster reads as
            // a unit rather than three loose glyphs. Favourite stays inline (a glanceable toggle) and
            // archive/trash/details stay behind the overflow for the width reason they always were:
            // four separate 48dp buttons starved the date column.
            GlassPill {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (flags.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(
                            if (flags.isFavorite) R.string.viewer_unfavorite else R.string.viewer_favorite,
                        ),
                        tint = if (flags.isFavorite) TelepicTokens.colors.favoriteAmber else Color.White,
                    )
                }
                // The per-item backup control sits beside the favourite star, in the band that
                // carries this item's own state, rather than behind the overflow: "is this one backed
                // up?" is a question the user asks while looking at the photo.
                BackupControl(state = backupState, busy = backupBusy, onBackup = onBackup)
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
}

/**
 * The single-item backup control, in the viewer's action band beside the favourite star.
 *
 * It deliberately uses the *same* glyphs and the *same* strings the grid's tile badge uses — a
 * cloud-upload when there is work to do, a cloud-check once it is done, the `backup_state_*` wording —
 * so "is this one backed up?" is answered identically in the viewer and in the grid instead of growing
 * a second vocabulary. The one new thing is the success tint: green is reserved for the state the user
 * is waiting for.
 *
 * Nothing here is inferred. `BACKED_UP` arrives from the queue row the worker writes only after
 * Telegram confirmed a real remote identity, so the green check cannot appear for a photo that was
 * merely hashed or queued. The states with nothing to do render as plain 48dp glyphs rather than
 * disabled buttons: a control that cannot do anything is not a button, and should not be announced as
 * one.
 */
@Composable
private fun BackupControl(
    state: MediaBackupVisualState,
    busy: Boolean,
    onBackup: () -> Unit,
) {
    val success = TelepicTokens.colors.mediaSuccessText
    when {
        busy || state == MediaBackupVisualState.UPLOADING -> BackupGlyph(
            description = stringResource(R.string.backup_state_uploading),
        ) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        }
        state == MediaBackupVisualState.BACKED_UP -> BackupGlyph(
            description = stringResource(R.string.backup_state_backed_up),
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudDone,
                contentDescription = null,
                tint = success,
            )
        }
        state == MediaBackupVisualState.QUEUED -> BackupGlyph(
            description = stringResource(R.string.backup_state_queued),
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudUpload,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
            )
        }
        else -> {
            val failed = state == MediaBackupVisualState.FAILED || state == MediaBackupVisualState.STALLED
            IconButton(onClick = onBackup) {
                Icon(
                    imageVector = Icons.Outlined.CloudUpload,
                    // The *action*, not the state: this is the one tappable case, and after a failure
                    // "Back up again" is what a tap does.
                    contentDescription = stringResource(
                        if (failed) R.string.viewer_backup_retry else R.string.photos_selection_backup,
                    ),
                    tint = when (state) {
                        MediaBackupVisualState.FAILED -> TelepicTokens.colors.mediaErrorText
                        // An on-media tone, not `colorScheme.tertiary`: the backdrop here is black in
                        // both schemes, so the light scheme's mid-blue tertiary measured ~2.5:1 and
                        // the warning marker was effectively invisible.
                        MediaBackupVisualState.STALLED -> TelepicTokens.colors.mediaWarningText
                        else -> Color.White
                    },
                )
            }
        }
    }
}

/** A 48dp slot holding a non-actionable state glyph, so the bar's rhythm does not shift with state. */
@Composable
private fun BackupGlyph(description: String, glyph: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(TelepicTokens.spacing.touchTarget)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        glyph()
    }
}

@Composable
private fun ViewerBottomBar(
    state: ViewerUiState,
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
        // Centred, so the save/download actions sit on the photo's midline instead of being split to
        // the two far edges around an empty middle.
        horizontalArrangement = Arrangement.spacedBy(spacing.md, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            onRestore != null -> GlassPill(modifier = Modifier.height(spacing.touchTarget)) {
                RestoreAction(onRestore = onRestore, restore = restore)
            }
            showDownload -> GlassPill(modifier = Modifier.height(spacing.touchTarget)) {
                if (state.download == CloudDownload.Downloading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp))
                } else {
                    TextButton(onClick = onDownload) {
                        Icon(Icons.Outlined.CloudDownload, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.viewer_download), color = Color.White, modifier = Modifier.padding(start = spacing.xs))
                    }
                }
            }
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
        ViewerMessage.QueuedForBackup -> R.string.viewer_backup_queued_message
        ViewerMessage.AlreadyBackedUp -> R.string.viewer_backup_already_message
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
        GlassMedallion(imageVector = Icons.Outlined.PhotoLibrary)
        Text(stringResource(R.string.viewer_missing), style = MaterialTheme.typography.bodyLarge, color = Color.White)
        TextButton(onClick = onBack) { Text(stringResource(R.string.viewer_back), color = Color.White) }
    }
}

/**
 * The hosting Activity's window, unwrapping any [ContextWrapper] between the composition's context and
 * it. Null when the composition is not inside an Activity (a preview), which the caller treats as
 * "leave the system bars alone" rather than as a failure.
 */
internal fun Context.hostingWindow(): Window? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.window
        current = current.baseContext
    }
    return null
}
