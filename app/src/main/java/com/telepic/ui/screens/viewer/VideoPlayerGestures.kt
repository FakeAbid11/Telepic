package com.telepic.ui.screens.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import com.telepic.navigation.MediaSource
import kotlin.math.abs

/** Which of the two drag axes a gesture committed to, decided once per gesture by the first slop. */
private enum class DragAxis { HORIZONTAL, VERTICAL }

/**
 * The video surface's whole gesture language, in one place: tap toggles the chrome, double-tap seeks
 * (or plays/pauses in the centre), long-press holds a speed boost, a horizontal drag pages to the next
 * or previous clip, and a vertical drag tunes brightness (left half) or volume (right half).
 *
 * Built from two pointer handlers that shadow each other cleanly:
 *
 * * **taps** run through [detectTapGestures], which asks for an *unconsumed* down — so a tap that
 *   lands on the transport (the play button, the seek slider, the save action) is claimed by that
 *   control and never reaches here.
 * * **drags** run through one [awaitEachGesture] pass that commits to a single axis the moment the
 *   pointer clears the touch slop, then stays on it. A drag that some overlay (the seek slider) is
 *   already consuming is abandoned — checked per move via `change.isConsumed` — so scrubbing never
 *   also pages or changes the volume.
 *
 * [onLevel] receives a *signed fraction delta* for each vertical move (a full-height drag sweeps the
 * whole range); the caller owns the current brightness/volume and clamps. [previous], [next] and every
 * callback are read through [rememberUpdatedState], because the neighbours can arrive in a later state
 * emission than the one that first keyed the gesture.
 */
@Composable
internal fun Modifier.videoPlayerGestures(
    key: Any?,
    previous: MediaSource?,
    next: MediaSource?,
    onNavigate: (MediaSource) -> Unit,
    onToggleControls: () -> Unit,
    onDoubleTapCenter: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSpeedBoost: (Boolean) -> Unit,
    onLevel: (LevelKind, Float) -> Unit,
): Modifier {
    val currentPrevious by rememberUpdatedState(previous)
    val currentNext by rememberUpdatedState(next)
    val currentOnNavigate by rememberUpdatedState(onNavigate)
    val currentOnToggle by rememberUpdatedState(onToggleControls)
    val currentOnDoubleCenter by rememberUpdatedState(onDoubleTapCenter)
    val currentOnSeekBy by rememberUpdatedState(onSeekBy)
    val currentOnSpeed by rememberUpdatedState(onSpeedBoost)
    val currentOnLevel by rememberUpdatedState(onLevel)

    return this
        .pointerInput(key) {
            detectTapGestures(
                onTap = { currentOnToggle() },
                onDoubleTap = { offset ->
                    val width = size.width.toFloat()
                    when (VideoGestureMath.doubleTapZone(offset.x, width)) {
                        DoubleTapZone.BACKWARD -> currentOnSeekBy(-VideoGestureMath.DOUBLE_TAP_SEEK_MS)
                        DoubleTapZone.FORWARD -> currentOnSeekBy(VideoGestureMath.DOUBLE_TAP_SEEK_MS)
                        DoubleTapZone.CENTER -> currentOnDoubleCenter()
                    }
                },
                onLongPress = { currentOnSpeed(true) },
                // Runs for every press; releasing a press that was never a long-press is a cheap no-op
                // in the caller (it only restores the speed when one was actually boosted).
                onPress = {
                    tryAwaitRelease()
                    currentOnSpeed(false)
                },
            )
        }
        .pointerInput(key) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val width = size.width.toFloat()
                val height = size.height.toFloat()
                val kind = VideoGestureMath.levelKindFor(down.position.x, width)
                var axis: DragAxis? = null
                var totalX = 0f
                var totalY = 0f
                var last = down.position
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    val change = pressed.first()
                    // An overlay (the seek slider) is driving this drag — leave it alone, and drop any
                    // axis we had started to claim so a half-swipe never pages by accident.
                    if (change.isConsumed) {
                        axis = null
                        break
                    }
                    val position = change.position
                    val delta = if (last != Offset.Unspecified) position - last else Offset.Zero
                    last = position
                    if (axis == null) {
                        totalX += delta.x
                        totalY += delta.y
                        val slop = viewConfiguration.touchSlop
                        if (abs(totalX) > slop || abs(totalY) > slop) {
                            axis = if (abs(totalX) > abs(totalY)) DragAxis.HORIZONTAL else DragAxis.VERTICAL
                        }
                    }
                    when (axis) {
                        DragAxis.VERTICAL -> currentOnLevel(kind, VideoGestureMath.levelDelta(delta.y, height))
                        else -> Unit
                    }
                    if (axis != null) change.consume()
                }
                if (axis == DragAxis.HORIZONTAL) {
                    SwipeResolver.resolve(
                        deltaPx = totalX,
                        widthPx = width,
                        previous = currentPrevious,
                        next = currentNext,
                    )?.let(currentOnNavigate)
                }
            }
        }
}
