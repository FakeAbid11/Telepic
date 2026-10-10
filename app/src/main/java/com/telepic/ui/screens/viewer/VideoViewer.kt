package com.telepic.ui.screens.viewer

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * The Viewer's video surface, isolated so Media3 lifecycle and any device quirks live in one place.
 * Local videos play straight from their content URI; a cloud video plays only after its original has
 * been explicitly downloaded (there is no verified Telegram streaming path — so no fake "streaming"
 * is claimed). Real playback is device-validated; this component is deliberately not exercised by
 * Robolectric. Media3's player UI is still an opt-in (unstable) surface, scoped to this file only.
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

    Box(modifier = Modifier.fillMaxSize()) {
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
    }
}

/**
 * Builds an ExoPlayer prepared with [uri]. Guarded so an unsupported device/codec never crashes the
 * Viewer — failures degrade to a playback-error state on the player itself.
 */
private fun buildPlayer(context: Context, uri: Uri): ExoPlayer =
    ExoPlayer.Builder(context).build().apply {
        // Route audio through the media stream and let Media3 own audio focus (ducks/stops for
        // calls and other focus requests instead of fighting them).
        setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
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
