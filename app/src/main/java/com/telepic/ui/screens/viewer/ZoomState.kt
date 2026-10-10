package com.telepic.ui.screens.viewer

/**
 * Pure, testable pinch/pan transform state for the Viewer's zoomable image. Kept free of Compose so
 * the gesture math is unit-tested directly. Scale is clamped to [MIN_SCALE]..[MAX_SCALE]; panning is
 * only meaningful when zoomed in, and [reset] returns to fit-to-screen.
 *
 * [onPan]/[bounded] accept the pannable half-extent ([boundX]/[boundY], derived by the caller from
 * the widget size and current scale) so a zoomed image can be dragged at most until its far edge
 * reaches the viewport center — never fully off-screen. Defaults are unlimited, preserving the
 * plain pass-through behavior existing callers/tests rely on.
 */
data class ZoomState(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {

    val isZoomed: Boolean get() = scale > 1f + EPSILON

    fun onScale(factor: Float): ZoomState =
        copy(scale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)).bounded()

    fun onPan(
        dx: Float,
        dy: Float,
        boundX: Float = Float.MAX_VALUE,
        boundY: Float = Float.MAX_VALUE,
    ): ZoomState =
        if (isZoomed) copy(offsetX = offsetX + dx, offsetY = offsetY + dy).bounded(boundX, boundY) else this

    /** Collapses to identity at fit-to-screen; otherwise clamps offsets to ±bound. */
    fun bounded(boundX: Float = Float.MAX_VALUE, boundY: Float = Float.MAX_VALUE): ZoomState {
        if (scale <= 1f + EPSILON) return ZoomState()
        return copy(
            offsetX = offsetX.coerceIn(-boundX, boundX),
            offsetY = offsetY.coerceIn(-boundY, boundY),
        )
    }

    fun reset(): ZoomState = ZoomState()

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
        private const val EPSILON = 0.001f
    }
}
