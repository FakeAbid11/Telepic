package com.telepic.ui.screens.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import com.telepic.navigation.MediaSource

/**
 * Swipe-to-page for a surface with no zoom of its own — the video player.
 *
 * The photo path carries its own gesture because it shares one pass with pinch/pan zoom
 * (see `ViewerContent`). The video surface has nothing to zoom and nothing to fight: media3's
 * `PlayerView` runs with `useController = false`, so with no controller it installs no touch listener
 * and does not consume events, leaving them for this detector. A drag is accumulated and resolved by
 * [SwipeResolver] on release — left pages to the older neighbour ([next]), right to the newer one
 * ([previous]) — with the same 25%-width threshold the photo uses.
 *
 * There is deliberately no drag-follow offset: the player draws through a `SurfaceView`, which does
 * not move with a Compose translation, so tracking the finger would slide only the overlays and look
 * broken. The page change lands on release instead.
 *
 * [previous], [next] and [onNavigate] are read through [rememberUpdatedState]: the neighbours can
 * arrive in a later state emission than the one that first keyed the gesture, and a captured copy
 * would then navigate by a stale pair.
 */
@Composable
internal fun Modifier.swipeToNavigate(
    key: Any?,
    previous: MediaSource?,
    next: MediaSource?,
    onNavigate: (MediaSource) -> Unit,
): Modifier {
    val currentPrevious by rememberUpdatedState(previous)
    val currentNext by rememberUpdatedState(next)
    val currentOnNavigate by rememberUpdatedState(onNavigate)
    return pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var dragTotalX = 0f
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                val change = pressed.first()
                val delta = if (change.previousPosition != Offset.Unspecified) {
                    change.position - change.previousPosition
                } else {
                    Offset.Zero
                }
                dragTotalX += delta.x
                change.consume()
            }
            if (dragTotalX != 0f) {
                SwipeResolver.resolve(
                    deltaPx = dragTotalX,
                    widthPx = size.width.toFloat(),
                    previous = currentPrevious,
                    next = currentNext,
                )?.let(currentOnNavigate)
            }
        }
    }
}
