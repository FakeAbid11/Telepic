package com.telepic.ui.screens.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
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
 * Two handlers run together, split so neither can starve the other:
 *
 * * **drags** run one [awaitEachGesture] pass on the [PointerEventPass.Initial] pass. Observing on
 *   `Initial` — and consuming there — means the drag reads the raw, *unconsumed* movement and claims it
 *   before the tap detector (which runs on the later `Main` pass and would otherwise consume the same
 *   moves while deciding a press is not a tap) ever sees them. That is what makes the horizontal page
 *   reliable rather than order-dependent. A drag that begins over the visible transport is skipped
 *   entirely ([excludeBottomPx]) so consuming on `Initial` never breaks the seek slider.
 * * **taps** run through [detectTapGestures] on the normal pass. A drag's movement is already consumed
 *   by then, so it cancels its own tap; a real tap carries no movement and is delivered untouched.
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
    excludeBottomPx: Float = 0f,
): Modifier {
    val currentPrevious by rememberUpdatedState(previous)
    val currentNext by rememberUpdatedState(next)
    val currentOnNavigate by rememberUpdatedState(onNavigate)
    val currentOnToggle by rememberUpdatedState(onToggleControls)
    val currentOnDoubleCenter by rememberUpdatedState(onDoubleTapCenter)
    val currentOnSeekBy by rememberUpdatedState(onSeekBy)
    val currentOnSpeed by rememberUpdatedState(onSpeedBoost)
    val currentOnLevel by rememberUpdatedState(onLevel)
    val currentExcludeBottom by rememberUpdatedState(excludeBottomPx)

    return this
        .pointerInput(key) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
                val width = size.width.toFloat()
                val height = size.height.toFloat()
                // A press that starts on the transport belongs to the transport (the seek slider,
                // the buttons); claiming it here would fight the slider for the same drag.
                if (down.position.y >= height - currentExcludeBottom) return@awaitEachGesture

                val kind = VideoGestureMath.levelKindFor(down.position.x, width)
                var axis: DragAxis? = null
                var totalX = 0f
                var totalY = 0f
                var last = down.position
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    val change = pressed.first()
                    val delta = change.position - last
                    last = change.position
                    // Accumulate the *whole* gesture, not just up to the lock: the horizontal
                    // displacement at lift-off is what [SwipeResolver] measures against the page
                    // threshold, so freezing it at the slop crossing would always undershoot.
                    totalX += delta.x
                    totalY += delta.y
                    if (axis == null) {
                        val slop = viewConfiguration.touchSlop
                        if (abs(totalX) > slop || abs(totalY) > slop) {
                            axis = if (abs(totalX) > abs(totalY)) DragAxis.HORIZONTAL else DragAxis.VERTICAL
                        }
                    }
                    when (axis) {
                        DragAxis.HORIZONTAL -> change.consume()
                        DragAxis.VERTICAL -> {
                            change.consume()
                            currentOnLevel(kind, VideoGestureMath.levelDelta(delta.y, height))
                        }
                        null -> Unit
                    }
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
                //
                // The release is in a `finally` so the boost is **always** undone. `tryAwaitRelease`
                // can end without returning — the press can be cancelled, or the whole gesture
                // coroutine can be cancelled (the item changed, the screen was left) — and a boost
                // that outlived its gesture left the video running at 2x with no visible cue.
                onPress = {
                    try {
                        tryAwaitRelease()
                    } finally {
                        currentOnSpeed(false)
                    }
                },
            )
        }
}
