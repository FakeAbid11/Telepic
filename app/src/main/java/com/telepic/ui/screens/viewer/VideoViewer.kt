package com.telepic.ui.screens.viewer

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.telepic.domain.media.DurationLabel
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.telepic.navigation.MediaSource
import com.telepic.ui.theme.TelepicTokens
import java.io.File
import kotlin.math.roundToInt
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
 * [VideoTransport] owns the one bar that's left — a seek line over a control row carrying play/pause,
 * mute and the cloud save action — sized to `videoTransportHeight`.
 *
 * Following NextPlayer's shape, the surface is **full-bleed** and the chrome floats over it: a centre
 * play/pause medallion, the bottom transport, and a transient [VideoGestureHud], all dismissed by a
 * tap and auto-hidden ~3s after the last interaction *while playing*. The picture owns the whole
 * screen and the whole gesture language ([videoPlayerGestures]) — double-tap to seek, vertical drag
 * for brightness/volume, long-press for a speed boost, horizontal drag to page — while the controls,
 * composed on top, keep first refusal on any touch.
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
    onNavigate: (MediaSource) -> Unit,
    modifier: Modifier = Modifier,
    onRestore: (() -> Unit)? = null,
    restore: RestoreState = RestoreState.Idle,
    startMuted: Boolean = true,
    controlsVisible: Boolean = true,
    onSetControlsVisible: (Boolean) -> Unit = {},
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
    val density = LocalDensity.current
    // While the chrome is up, its bottom band belongs to the transport, not to the page/level
    // gestures: the gesture detector skips any press that starts down there. Measured in px so the
    // detector can compare against its own pointer-space height.
    val controlsBandPx = if (controlsVisible) {
        with(density) { spacing.videoTransportHeight.toPx() } +
            WindowInsets.navigationBars.getBottom(density).toFloat()
    } else {
        0f
    }

    // Full-bleed: the video fills the window, and every control is drawn *over* it, exactly as the
    // photo chrome floats over the image. Reserving a band for the transport used to keep the surface
    // clear of the chrome, but it shrank the picture and blocked the NextPlayer-style gestures (a
    // surface padded out of the transport band could not see a vertical drag that started on it).
    // Instead the transport is composed on top with its own scrim, and the full-surface gesture
    // detector simply skips a press that starts in the transport's band (see `videoPlayerGestures`).
    val brightness = rememberScreenBrightnessController()
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    // Left-half drags own this; seeded from the window's current override (or the middle when the
    // window follows the system, since the true system brightness needs a permission to read).
    var brightnessFraction by remember(videoUri, brightness) {
        mutableFloatStateOf(brightness?.initialFraction ?: 0.5f)
    }
    var boosting by remember(videoUri) { mutableStateOf(false) }
    var feedback by remember(videoUri) { mutableStateOf<VideoGestureFeedback?>(null) }
    // Bumped by every gesture so the auto-hide timer restarts on interaction.
    var interactionToken by remember(videoUri) { mutableIntStateOf(0) }

    // The confirmation clears itself a beat after the last gesture. Keyed on the value, so a stream of
    // volume/level updates keeps the HUD up and only the *final* one starts the countdown.
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(FEEDBACK_VISIBLE_MS)
            feedback = null
        }
    }

    // Chrome hides ~3s after the last interaction, but only while the video is actually playing: a
    // paused clip keeps its controls, because with nothing moving the controls *are* the screen.
    LaunchedEffect(controlsVisible, playback.state.isPlaying, interactionToken) {
        if (controlsVisible && playback.state.isPlaying) {
            delay(AUTO_HIDE_DELAY_MS)
            onSetControlsVisible(false)
        }
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    val applySeek: (Long) -> Unit = { deltaMs ->
        val duration = player.duration
        val target = player.currentPosition + deltaMs
        player.seekTo(if (duration > 0L) target.coerceIn(0L, duration) else target.coerceAtLeast(0L))
        playback.tick(player) // reflect the jump immediately, not on the next 250ms poll
        feedback = VideoGestureFeedback.Seek(deltaMs, deltaMs > 0)
        interactionToken++
    }

    val applySpeed: (Boolean) -> Unit = { start ->
        if (start) {
            if (!boosting) {
                boosting = true
                player.setPlaybackSpeed(VideoGestureMath.SPEED_BOOST_MULTIPLIER)
                feedback = VideoGestureFeedback.SpeedBoost(VideoGestureMath.SPEED_BOOST_MULTIPLIER)
            }
        } else if (boosting) {
            boosting = false
            player.setPlaybackSpeed(1f)
            if (feedback is VideoGestureFeedback.SpeedBoost) feedback = null
        }
        interactionToken++
    }

    val applyLevel: (LevelKind, Float) -> Unit = { kind, delta ->
        when (kind) {
            LevelKind.BRIGHTNESS -> {
                val next = VideoGestureMath.clampLevel(brightnessFraction + delta)
                brightnessFraction = brightness?.set(next) ?: next
                feedback = VideoGestureFeedback.Level(LevelKind.BRIGHTNESS, brightnessFraction)
            }
            LevelKind.VOLUME -> {
                // System stream volume, so the gesture leaves the hardware keys' own model intact.
                // Raising it while muted is the one thing that also unmutes: a user who asks for
                // volume wants sound, and that self-serve unmute is exactly the "explicit request"
                // the player is built to wait for. Dragging to 0 does *not* mute — the mute button
                // stays authoritative.
                val stream = AudioManager.STREAM_MUSIC
                val max = audioManager?.getStreamMaxVolume(stream) ?: 0
                if (audioManager != null && max > 0) {
                    val current = audioManager.getStreamVolume(stream).toFloat() / max
                    val next = VideoGestureMath.clampLevel(current + delta)
                    val target = (next * max).roundToInt().coerceIn(0, max)
                    audioManager.setStreamVolume(stream, target, 0)
                    if (target > 0 && muted) muted = false
                    feedback = VideoGestureFeedback.Level(LevelKind.VOLUME, next)
                }
            }
        }
        interactionToken++
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false // the transport below is the controller
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view -> view.player = player },
            onRelease = { view -> view.player = null },
            // The full surface owns the gestures; controls composed after it (below) get first refusal
            // on a touch and consume their own drags.
            modifier = Modifier
                .fillMaxSize()
                .videoPlayerGestures(
                    key = state.source,
                    previous = state.previous,
                    next = state.next,
                    onNavigate = onNavigate,
                    onToggleControls = {
                        onSetControlsVisible(!controlsVisible)
                        interactionToken++
                    },
                    onDoubleTapCenter = { togglePlay(); interactionToken++ },
                    onSeekBy = applySeek,
                    onSpeedBoost = applySpeed,
                    onLevel = applyLevel,
                    excludeBottomPx = controlsBandPx,
                ),
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

        // The centre play/pause medallion, part of the chrome: an obvious target when the transport's
        // own button is a long way down the frame.
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            CenterPlayButton(isPlaying = playback.state.isPlaying, onClick = { togglePlay(); interactionToken++ })
        }

        // The bottom chrome: a scrim for legibility plus the two-tier transport. It slides away with
        // the rest of the chrome, so nothing dark lingers over the picture.
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Fades from clear at the top to a dark base under the controls, so white glyphs
                    // stay legible over a bright frame without blackening the whole band. The
                    // navigation-bar inset keeps the transport clear of the system bar the video
                    // now runs behind.
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                        ),
                    )
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                Spacer(modifier = Modifier.height(spacing.lg))
                VideoTransport(
                    state = playback.state,
                    onTogglePlay = { togglePlay() },
                    onSeekTo = { target -> player.seekTo(target) },
                    // The mute affordance lives on the transport now (media3 1.4.1 exposes no volume
                    // control and ships no mute button). It used to float top-right, where it competed
                    // with the top-bar's own actions; on the bar it reads as one of the transport's
                    // controls.
                    muted = muted,
                    onToggleMute = { muted = !muted },
                    onRestore = onRestore,
                    restore = restore,
                )
            }
        }

        // The transient answer to a gesture — the seek jump, the level, the speed — over everything.
        VideoGestureHud(feedback = feedback)
    }
}

/**
 * The large glass play/pause medallion at the centre of a video. Reuses the transport's drawn pause
 * glyph, because `Icons.Filled.Pause` is not in `material-icons-core`.
 */
@Composable
private fun CenterPlayButton(isPlaying: Boolean, onClick: () -> Unit) {
    val playDescription = stringResource(R.string.viewer_video_play)
    val pauseDescription = stringResource(R.string.viewer_video_pause)
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(72.dp)
            .glassSurface(CircleShape),
    ) {
        if (isPlaying) {
            PauseGlyph(
                modifier = Modifier.semantics { contentDescription = pauseDescription },
            )
        } else {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = playDescription,
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

/**
 * Formats a playback position as `m:ss`, or `h:mm:ss` once an hour is exceeded — a long recording
 * must not silently lose its hour. Pure, so the transport's clock is testable without a player.
 *
 * Delegates to [DurationLabel] rather than carrying its own copy of the rule: a 90-minute clip has to
 * read `1:30:00` in the transport exactly as it does on its thumbnail. The only difference is the
 * zero case — a player sitting at position 0 genuinely has a position to show, whereas a tile with an
 * unknown duration has nothing, so the transport supplies its own `0:00` for the null.
 */
internal fun formatPlaybackTime(millis: Long): String = DurationLabel.format(millis) ?: "0:00"

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
 * The video's bottom bar, laid out in two tiers inside `TelepicSpacing.videoTransportHeight`. It is
 * drawn over the full-bleed video rather than inside a reserved band. The seek slider takes the top
 * tier on its own, so it is wide
 * and grabbable instead of sharing one cramped row with many controls; the control row below carries
 * play / pause, mute and the cloud save action.
 *
 * Icon rather than label: a 360dp phone has no room for words once the clock shares the bar. The
 * labels ride on content descriptions, and every one of them names the *action* (a screen reader user
 * hears what the tap does, not what the icon shows).
 */
@Composable
internal fun VideoTransport(
    state: VideoTransportState,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = true,
    onToggleMute: () -> Unit = {},
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
    val playDescription = stringResource(R.string.viewer_video_play)
    val pauseDescription = stringResource(R.string.viewer_video_pause)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(spacing.videoTransportHeight)
            .padding(horizontal = spacing.xs)
            .testTag(TAG_VIDEO_TRANSPORT),
    ) {
        // Tier 1 — the seek line, full width, with the clock at each end.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatPlaybackTime(displayPositionMs),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                // Ellipsis, not the default Clip: at a large font scale a clipped duration was cut
                // mid-glyph with no indication anything was missing.
                overflow = TextOverflow.Ellipsis,
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
                text = if (state.isSeekable) formatPlaybackTime(state.durationMs) else stringResource(R.string.viewer_duration_unknown),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(horizontal = spacing.xs),
            )
        }
        // Tier 2 — the transport controls.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            // Playback on the leading side, output controls (mute, save) pushed to the trailing edge.
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleMute) {
                Icon(
                    imageVector = if (muted) {
                        Icons.AutoMirrored.Filled.VolumeOff
                    } else {
                        Icons.AutoMirrored.Filled.VolumeUp
                    },
                    // Describes the ACTION, not the state: a screen reader user hears what a tap does.
                    contentDescription = stringResource(
                        if (muted) R.string.viewer_unmute else R.string.viewer_mute,
                    ),
                    tint = Color.White,
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

/** How long the chrome lingers after the last interaction before hiding, while playing. */
private const val AUTO_HIDE_DELAY_MS = 3_000L

/** How long a gesture's on-screen confirmation (seek/level/speed) stays before fading. */
private const val FEEDBACK_VISIBLE_MS = 900L
