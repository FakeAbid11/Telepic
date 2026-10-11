package com.telepic.ui.screens.viewer

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
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

/**
 * The Viewer's video surface, isolated so Media3 lifecycle and any device quirks live in one place.
 * Local videos play straight from their content URI; a cloud video plays only after its original has
 * been explicitly downloaded (there is no verified Telegram streaming path — so no fake "streaming"
 * is claimed). Real playback is device-validated; this component is deliberately not exercised by
 * Robolectric. Media3's player UI is still an opt-in (unstable) surface, scoped to this file only.
 *
 * Videos open **muted**. Autoplaying with sound hijacks whatever the user was already listening to,
 * and media3 gives no middle ground: while the player holds audio focus it ducks or stops other apps,
 * and `setVolume` never releases that focus. So the player is built with `handleAudioFocus = false`
 * and only claims focus at the moment the user actually asks for sound via the unmute control.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoViewer(state: ViewerUiState, onDownload: () -> Unit) {
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

    // Mute is per-video user input, not seed data: moving to the next clip starts muted again.
    var muted by remember(videoUri) { mutableStateOf(true) }
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

    val spacing = TelepicTokens.spacing

    // The video surface gets the frame the chrome leaves free — the system-bar inset plus one chrome
    // bar at each end — instead of the window. The chrome is composed *over* this component, and on
    // video it is always visible: ViewerContent hands videos here without wiring onRequestToggle, so
    // no tap can dismiss it. media3 lays its controller out against PlayerView's edges, so a
    // window-sized PlayerView slid three things under the chrome: the controller's own bottom bar
    // (60dp — the same strip the Viewer's navigation row occupies), its progress line (the bottom of
    // a 48dp band, 52dp up from PlayerView's bottom), and the whole-surface dim (#98000000) that
    // fills PlayerView whenever the controller is shown. The collision was functional, not just
    // cosmetic: media3's right-most bottom button sat exactly under the Viewer's "next" arrow, which
    // is composed later, wins the hit test, and swallowed the taps.
    //
    // Reserving the band removes the intersection, keeps the dim off the chrome, and makes the
    // aspect-fit centre coincide with the visible area. The reserved strip is indistinguishable from
    // media3's own letterbox — the backdrop behind this component is black in both schemes.
    //
    // Reserved at both ends unconditionally: the bottom chrome is conditional (navigation row,
    // otherwise a lone "Save to device" action, otherwise nothing) but the navigation row is the
    // tallest of the three, so one reservation covers them all and the video never changes size as
    // the user moves between items.
    val chromeBarHeight = spacing.chromeBarHeight

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(top = chromeBarHeight, bottom = chromeBarHeight),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view -> view.player = player },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
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
 * Video audio routed as media/movie content. `CONTENT_TYPE_MOVIE` (rather than the default
 * `CONTENT_TYPE_UNKNOWN`) lets the platform apply movie-appropriate ducking and volume behaviour.
 */
private val movieAudioAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
    .build()

/**
 * Builds an ExoPlayer prepared with [uri], starting muted and holding no audio focus. Guarded so an
 * unsupported device/codec never crashes the Viewer — failures degrade to a playback-error state on
 * the player itself.
 */
private fun buildPlayer(context: Context, uri: Uri): ExoPlayer =
    ExoPlayer.Builder(context).build().apply {
        // handleAudioFocus = false: an autoplaying video must not duck or stop whatever the user is
        // already listening to. Focus is claimed later, only when the user unmutes.
        setAudioAttributes(movieAudioAttributes, /* handleAudioFocus = */ false)
        volume = 0f
        setMediaItem(MediaItem.fromUri(uri))
        addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // Surfaced by the PlayerView's built-in error UI; nothing to fabricate here.
                }
            },
        )
        prepare()
        playWhenReady = true
    }
