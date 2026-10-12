package com.telepic.ui.screens.viewer

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.telepic.R
import com.telepic.ui.theme.TelepicTokens
import java.io.File
import kotlinx.coroutines.delay

/**
 * The Viewer's video surface, isolated so Media3 lifecycle and any device quirks live in one place.
 * Local videos play straight from their content URI; a cloud video plays only after its original has
 * been explicitly downloaded (there is no verified Telegram streaming path — so no fake "streaming"
 * is claimed). Real playback is device-validated; the ExoPlayer wiring is deliberately not exercised
 * by Robolectric, while the transport it drives *is* (see `VideoTransportUiTest`) — the bar takes
 * plain data, so it never needs a player to be asserted.
 *
 * The transport is Compose rather than media3's stock `PlayerView` controller. The stock controller
 * lays its own 60dp bottom bar out against `PlayerView`'s edges, which put a *second* control strip
 * directly above the Viewer's bottom chrome: two stacked bars, and the controller's right-most
 * button used to sit exactly under the Viewer's "next" arrow (composed later, so it won the hit test
 * and swallowed the taps). `useController = false` removes media3's chrome entirely, and
 * [VideoTransport] owns the one bar that's left — play/pause, seek, the clock, prev/next and the
 * cloud save action — sized to `chromeBarHeight`, the same band the surface reserves for it.
 *
 * Videos open **muted** by default ([startMuted], from the "Start videos muted" preference).
 * Autoplaying with sound hijacks whatever the user was already listening to, and media3 gives no
 * middle ground: while the player holds audio focus it ducks or stops other apps, and `setVolume`
 * never releases that focus. So the player is built with `handleAudioFocus = false` and only claims
 * focus at the moment the user actually asks for sound via the unmute control — which is also why the
 * preference changes the *initial* state only, and never mutes a video the user has already unmuted.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoViewer(
    state: ViewerUiState,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onRestore: (() -> Unit)? = null,
    restore: RestoreState = RestoreState.Idle,
    startMuted: Boolean = true,
) {
    val videoUri: Uri? = when (val item = state.item) {
        is ViewerItem.Local -> item.media.contentUri
        is ViewerItem.Cloud -> item.originalPath?.let { Uri.fromFile(File(it)) }
        null -> null
    }

    if (videoUri == null) {
        // Cloud video not yet downloaded — an explicit, honest download step.
        CloudUnavailable(state = state, onDownload = onDownload)
        return
    }

    val context = LocalContext.current
    val player = remember(videoUri) { buildPlayer(context, videoUri) }
    DisposableEffect(player) {
        onDispose { player.release() } // never leak a player across navigation
    }

    // Backgrounding must silence playback: remember whether WE were playing (a user-paused video
    // must not resume itself) and pause/resume with the STOP/START lifecycle — not PAUSE/RESUME, so
    // a translucent overlay or the permission dialog never interrupts a video.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        var wasPlayingBeforeStop = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    wasPlayingBeforeStop = player.isPlaying
                    if (wasPlayingBeforeStop) player.pause()
                }
                Lifecycle.Event.ON_START -> if (wasPlayingBeforeStop) player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Mute is per-video user input, not seed data: moving to the next clip returns to [startMuted],
    // the value of the "Start videos muted" preference (true unless the user turned it off).
    var muted by remember(videoUri) { mutableStateOf(startMuted) }
    LaunchedEffect(player, muted) {
        if (muted) {
            // Silence first, then hand focus back — so no frame of sound outlives the focus request.
            player.volume = 0f
            player.setAudioAttributes(movieAudioAttributes, /* handleAudioFocus = */ false)
        } else {
            // Attributes first: this call is what makes media3 request audio focus (it re-runs
            // AudioFocusManager.updateAudioFocus internally). Volume second, so the app is never
            // audible before it has actually been granted focus.
            player.setAudioAttributes(movieAudioAttributes, /* handleAudioFocus = */ true)
            player.volume = 1f
        }
    }

    // The one listener that keeps [VideoTransportState] honest. Built here rather than inside
    // [buildPlayer] so the error path has a Compose-visible consequence: the stock controller used to
    // render media3's error UI, and with `useController = false` nothing does.
    val playback = remember(videoUri) { VideoPlaybackState() }
    var playbackFailed by remember(videoUri) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = playback.onIsPlayingChanged(isPlaying)

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) playback.tick(player)
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackFailed = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // Seed the clock before the first state callback can land (the player is already prepared and
    // `playWhenReady` when this composition starts).
    LaunchedEffect(player) { playback.tick(player) }
    // Position while playing. Keyed on the flag so the loop stops the moment playback pauses; a final
    // tick outside the loop pins the exact paused position instead of the last poll's approximation.
    LaunchedEffect(player, playback.state.isPlaying) {
        while (playback.state.isPlaying) {
            delay(POSITION_TICK_MS)
            playback.tick(player)
        }
        playback.tick(player)
    }

    val spacing = TelepicTokens.spacing

    // The video surface gets the frame the chrome leaves free — the system-bar inset plus one chrome
    // bar at each end — instead of the window. The chrome is composed *over* this component, and on
    // video it is always visible: ViewerContent hands videos here without wiring onRequestToggle, so
    // no tap can dismiss it.
    //
    // Reserving the band at the bottom is what makes room for the transport: the transport sits in
    // the last `chromeBarHeight` of this already-inset box, so it replaces the Viewer's bottom chrome
    // rather than stacking above it. The reserved strip is indistinguishable from media3's own
    // letterbox — the backdrop behind this component is black in both schemes.
    //
    // Reserved at both ends unconditionally: the top chrome is the only one left for a photo, and
    // keeping the same frame for both kinds means the video never changes size as the user swipes.
    val chromeBarHeight = spacing.chromeBarHeight

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(top = chromeBarHeight, bottom = chromeBarHeight),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false // the transport below is the controller
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view -> view.player = player },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        // Honest playback failure. media3's stock controller drew its own error view; ours must say
        // the same thing in our own words, and offer a real retry (re-prepare from the start).
        if (playbackFailed) {
            VideoPlaybackError(
                onRetry = {
                    playbackFailed = false
                    player.seekTo(0)
                    player.prepare()
                    player.play()
                },
            )
        }

        VideoTransport(
            state = playback.state,
            previousEnabled = state.previous != null,
            nextEnabled = state.next != null,
            onTogglePlay = { if (player.isPlaying) player.pause() else player.play() },
            onSeekTo = { target -> player.seekTo(target) },
            onPrevious = onPrevious,
            onNext = onNext,
            onRestore = onRestore,
            restore = restore,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // PlayerView exposes no mute/volume setter in media3 1.4.1, and its default controller
        // layout ships no mute button either (checked against exo_player_control_view.xml), so the
        // affordance is ours. It stays mounted whether or not the transport controls are shown.
        //
        // Positioned from the Box's content area, which already begins below the top bar. The button
        // used to repeat that inset itself; the duplication is what let it drift up into the bar's
        // Details action — composed later, so it also won the hit test and stole the taps.
        IconButton(
            onClick = { muted = !muted },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = spacing.xs)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)),
        ) {
            Icon(
                imageVector = if (muted) {
                    Icons.AutoMirrored.Filled.VolumeOff
                } else {
                    Icons.AutoMirrored.Filled.VolumeUp
                },
                // Describes the ACTION, not the state: a screen reader user hears what a tap will do.
                contentDescription = stringResource(
                    if (muted) R.string.viewer_unmute else R.string.viewer_mute,
                ),
                tint = Color.White,
            )
        }
    }
}

/**
 * Formats a playback position as `m:ss`, or `h:mm:ss` once an hour is exceeded — a long recording
 * must not silently lose its hour. Pure, so the transport's clock is testable without a player.
 */
internal fun formatPlaybackTime(millis: Long): String {
    if (millis <= 0L) return "0:00"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * What the video transport renders, as plain data. Deliberately holds no `Player` reference, so the
 * bar can be composed and asserted without a device-bound ExoPlayer.
 */
internal data class VideoTransportState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
) {
    /** 0..1 progress for the seek bar; 0 while the duration is unknown, so the thumb parks at the start. */
    val fraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** An item with no known duration cannot be seeked — the bar disables rather than snaps to a lie. */
    val isSeekable: Boolean
        get() = durationMs > 0L

    /**
     * Where a drag to [fraction] should land, or null when there is nothing to seek to. Clamped, so an
     * out-of-range fraction can never seek outside the media.
     */
    fun seekTarget(fraction: Float): Long? =
        if (durationMs <= 0L) null else (fraction.coerceIn(0f, 1f) * durationMs).toLong()

    /**
     * What the position clock shows: the scrub target while a drag is in progress ([dragFraction]
     * non-null), the last polled playback position otherwise. Hoisted out of the composable so the
     * rule is pinned by arithmetic — a gesture test cannot hold the drag open long enough to read it,
     * because the slider's own semantics action finishes the gesture as it reports the target.
     */
    fun displayPositionMs(dragFraction: Float?): Long =
        dragFraction?.let { seekTarget(it) } ?: positionMs
}

/**
 * The live mirror of [VideoTransportState] that follows one [Player]. The composition owns an
 * instance per video and feeds it from a `Player.Listener` plus a position tick while playing, so the
 * transport never reaches into the player itself.
 */
internal class VideoPlaybackState {
    var state by mutableStateOf(VideoTransportState())
        private set

    fun onIsPlayingChanged(isPlaying: Boolean) {
        state = state.copy(isPlaying = isPlaying)
    }

    fun onDurationChanged(durationMs: Long) {
        val duration = durationMs.coerceAtLeast(0L)
        // A freshly-resolved duration can be shorter than the last reported position (a re-seek, or a
        // stale callback); clamp, or the bar draws a thumb past its own end.
        val position = if (duration == 0L) state.positionMs else state.positionMs.coerceIn(0L, duration)
        state = state.copy(durationMs = duration, positionMs = position)
    }

    fun onPositionChanged(positionMs: Long) {
        val duration = state.durationMs
        val position = if (duration == 0L) {
            positionMs.coerceAtLeast(0L)
        } else {
            positionMs.coerceIn(0L, duration)
        }
        state = state.copy(positionMs = position)
    }

    /** Reads the player's whole clock at once, mapping media3's `TIME_UNSET` to 0 (unknown). */
    fun tick(player: Player) {
        onIsPlayingChanged(player.isPlaying)
        val duration = player.duration
        onDurationChanged(if (duration == C.TIME_UNSET) 0L else duration)
        onPositionChanged(player.currentPosition)
    }
}

/** The video's bottom bar. Tagged so a test can pin its height against the band the surface reserves. */
internal const val TAG_VIDEO_TRANSPORT = "video_transport"

/**
 * Stands in for the duration until the player reports one. Deliberately not `0:00` — an unknown
 * duration is not a zero-length video, and `formatPlaybackTime(0)` would claim exactly that, which
 * is the same lie the seek bar refuses to tell. `DurationLabel` makes the same call for badges.
 */
private const val UNKNOWN_DURATION_LABEL = "--:--"

/**
 * The video's only bottom bar. Sized to `TelepicSpacing.chromeBarHeight` — the same band the video
 * surface reserves for it and the same height the Viewer's photo chrome uses — and placed in the last
 * `chromeBarHeight` of the inset-adjusted surface, so it *is* the bar instead of a second one stacked
 * above it.
 *
 * Icon rather than label, because a 360dp phone has room for 0:12 · [seek] · 9:41 between two
 * navigation buttons and nothing else; the labels ride on content descriptions, and every one of them
 * names the *action* (a screen reader user hears what the tap does, not what the icon shows).
 */
@Composable
internal fun VideoTransport(
    state: VideoTransportState,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    onRestore: (() -> Unit)? = null,
    restore: RestoreState = RestoreState.Idle,
) {
    val spacing = TelepicTokens.spacing
    val seekDescription = stringResource(R.string.viewer_video_seek)
    // Drag-local thumb position: while the user drags, the bar follows the finger instead of being
    // yanked back to the last polled position on every 250ms tick. Deliberately un-keyed — keying it
    // on the state object would discard the drag the moment the poll advanced.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    // The clock tracks the scrub, not just the playback: a scrubbing user needs to read the position
    // they are aiming at, not the one they left.
    val displayPositionMs = state.displayPositionMs(dragFraction)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(spacing.chromeBarHeight)
            .padding(horizontal = spacing.xs)
            .testTag(TAG_VIDEO_TRANSPORT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, enabled = previousEnabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.viewer_previous),
                tint = chromeTint(previousEnabled),
            )
        }
        val playDescription = stringResource(R.string.viewer_video_play)
        val pauseDescription = stringResource(R.string.viewer_video_pause)
        IconButton(onClick = onTogglePlay) {
            if (state.isPlaying) {
                // Drawn, not imported: `Pause` is not part of `material-icons-core`, and pulling in
                // the extended set for one glyph is not a trade this app makes. The label rides on
                // the glyph's wrapper, which the button merges — the drawn bars carry no semantics.
                PauseGlyph(
                    modifier = Modifier.semantics { contentDescription = pauseDescription },
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = playDescription,
                    tint = Color.White,
                )
            }
        }
        Text(
            text = formatPlaybackTime(displayPositionMs),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(horizontal = spacing.xs),
        )
        Slider(
            value = dragFraction ?: state.fraction,
            // The seek is applied *while* dragging, not on release: scrubbing shows the frame the
            // finger is on instead of jumping once at the end. media3's seekTo is cheap and
            // idempotent, so this costs nothing and matches what the stock controller always did.
            onValueChange = { fraction ->
                dragFraction = fraction
                state.seekTarget(fraction)?.let(onSeekTo)
            },
            onValueChangeFinished = { dragFraction = null },
            enabled = state.isSeekable,
            colors = SliderDefaults.colors(
                // Explicit: the Material defaults (`primary` / `surfaceVariant`) are tuned for the
                // themed surface, and this bar sits on black.
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            // A bare slider announces its percentage, which says nothing about *what* is being
            // scrubbed; name the media position instead.
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = seekDescription },
        )
        Text(
            // Tied to `isSeekable`, not to a second `> 0` test: an unknown duration disables the
            // bar *and* must not be printed as a length.
            text = if (state.isSeekable) formatPlaybackTime(state.durationMs) else UNKNOWN_DURATION_LABEL,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 1,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(horizontal = spacing.xs),
        )
        IconButton(onClick = onNext, enabled = nextEnabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.viewer_next),
                tint = chromeTint(nextEnabled),
            )
        }
        if (onRestore != null) {
            IconButton(onClick = onRestore, enabled = restore !is RestoreState.Restoring) {
                when (restore) {
                    is RestoreState.Restoring -> CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                    is RestoreState.Restored -> Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = stringResource(R.string.viewer_saved),
                        tint = Color.White,
                    )
                    is RestoreState.Failed -> Icon(
                        imageVector = Icons.Outlined.SaveAlt,
                        // The reason rides on the description: a red icon alone cannot tell "no
                        // space" from "not available" apart.
                        contentDescription = stringResource(restore.reason.messageRes),
                        tint = TelepicTokens.colors.mediaErrorText,
                    )
                    RestoreState.Idle -> Icon(
                        imageVector = Icons.Outlined.SaveAlt,
                        contentDescription = stringResource(R.string.viewer_save_device),
                        tint = chromeTint(true),
                    )
                }
            }
        }
    }
}

/** White at full strength when the control can be used, dimmed when it cannot. */
private fun chromeTint(enabled: Boolean): Color =
    if (enabled) Color.White else Color.White.copy(alpha = 0.3f)

/** The 24dp Material pause glyph, drawn from two rounded bars at its proportions. */
@Composable
private fun PauseGlyph(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.size(24.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(2) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 16.dp)
                    .background(Color.White, RoundedCornerShape(1.dp)),
            )
        }
    }
}

/**
 * Shown when media3 reports a genuine playback failure, instead of the stock controller's own error
 * view (which `useController = false` took away). Same shape as the photo path's
 * [ViewerImageError] — white on the media backdrop, never `onSurface` — and it offers a real retry:
 * re-prepare the same player from the top, not a fabricated "it works now".
 */
@Composable
private fun VideoPlaybackError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TelepicTokens.spacing.md, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.viewer_playback_error),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) {
            Text(text = stringResource(R.string.common_retry), color = Color.White)
        }
    }
}

/**
 * Video audio routed as media/movie content. `CONTENT_TYPE_MOVIE` (rather than the default
 * `CONTENT_TYPE_UNKNOWN`) lets the platform apply movie-appropriate ducking and volume behaviour.
 */
private val movieAudioAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
    .build()

/**
 * Builds an ExoPlayer prepared with [uri], starting muted and holding no audio focus. Guarded so an
 * unsupported device/codec never crashes the Viewer — failures surface through the listener attached
 * in `VideoViewer` (a `PlaybackException` is a player *state*, not a crash).
 */
private fun buildPlayer(context: Context, uri: Uri): ExoPlayer =
    ExoPlayer.Builder(context).build().apply {
        // handleAudioFocus = false: an autoplaying video must not duck or stop whatever the user is
        // already listening to. Focus is claimed later, only when the user unmutes.
        setAudioAttributes(movieAudioAttributes, /* handleAudioFocus = */ false)
        volume = 0f
        setMediaItem(MediaItem.fromUri(uri))
        prepare()
        playWhenReady = true
    }

/** How often the running clock is re-read while a video plays. Fast enough to read as continuous. */
private const val POSITION_TICK_MS = 250L
