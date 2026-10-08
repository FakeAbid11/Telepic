package com.telepix.ui.screens.viewer

/**
 * Pure, testable pinch/pan transform state for the Viewer's zoomable image. Kept free of Compose so
 * the gesture math is unit-tested directly. Scale is clamped to [MIN_SCALE]..[MAX_SCALE]; panning is
 * only meaningful when zoomed in, and [reset] returns to fit-to-screen.
 */
data class ZoomState(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {

    val isZoomed: Boolean get() = scale > 1f + EPSILON

    fun onScale(factor: Float): ZoomState = copy(scale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)).reclamped()

    fun onPan(dx: Float, dy: Float): ZoomState =
        if (isZoomed) copy(offsetX = offsetX + dx, offsetY = offsetY + dy) else this

    fun reset(): ZoomState = ZoomState()

    /** Keep the offset within the pannable region so the image can't be dragged fully off-screen. */
    private fun reclamped(): ZoomState {
        if (scale <= 1f) return ZoomState()
        // Offsets are bounded by the caller using the widget size; here we only collapse fully at 1x.
        return this
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
        private const val EPSILON = 0.001f
    }
}
