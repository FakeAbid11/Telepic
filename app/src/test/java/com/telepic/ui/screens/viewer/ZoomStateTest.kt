package com.telepic.ui.screens.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure pinch/pan zoom math. */
class ZoomStateTest {

    @Test
    fun `starts unzoomed`() {
        assertFalse(ZoomState().isZoomed)
    }

    @Test
    fun `scale is clamped to the max`() {
        val zoomed = ZoomState().onScale(10f)
        assertEquals(ZoomState.MAX_SCALE, zoomed.scale, 0.001f)
        assertTrue(zoomed.isZoomed)
    }

    @Test
    fun `scale cannot go below one`() {
        assertEquals(ZoomState.MIN_SCALE, ZoomState().onScale(0.2f).scale, 0.001f)
    }

    @Test
    fun `panning is ignored while fit-to-screen`() {
        assertEquals(0f, ZoomState().onPan(50f, 50f).offsetX, 0.001f)
    }

    @Test
    fun `panning moves the image once zoomed`() {
        val panned = ZoomState().onScale(2f).onPan(30f, -10f)
        assertEquals(30f, panned.offsetX, 0.001f)
        assertEquals(-10f, panned.offsetY, 0.001f)
    }

    @Test
    fun `double-tap reset returns to fit`() {
        val reset = ZoomState().onScale(3f).onPan(10f, 10f).reset()
        assertEquals(1f, reset.scale, 0.001f)
        assertEquals(0f, reset.offsetX, 0.001f)
        assertFalse(reset.isZoomed)
    }

    // --- Pannable bounds: a zoomed image can never be dragged fully off-screen ------------------

    @Test
    fun `panning is clamped to the caller's bounds`() {
        val clamped = ZoomState().onScale(3f).onPan(1000f, -1000f, boundX = 120f, boundY = 200f)
        assertEquals(120f, clamped.offsetX, 0.001f)
        assertEquals(-200f, clamped.offsetY, 0.001f)
    }

    @Test
    fun `accumulated panning beyond the bound is held at it, not rejected`() {
        var zoom = ZoomState().onScale(2f)
        repeat(10) { zoom = zoom.onPan(50f, 0f, boundX = 100f, boundY = 100f) }
        assertEquals(100f, zoom.offsetX, 0.001f)
    }

    @Test
    fun `pinching out re-clamps stale offsets to the smaller pannable region`() {
        val over = ZoomState(scale = 5f, offsetX = 900f, offsetY = 0f).bounded(boundX = 100f, boundY = 100f)
        assertEquals(100f, over.offsetX, 0.001f)
    }

    @Test
    fun `scales back to fit collapse offsets even when bounded`() {
        val collapsed = ZoomState(scale = 4f, offsetX = 80f).onScale(0.1f)
        assertEquals(1f, collapsed.scale, 0.001f)
        assertEquals(0f, collapsed.offsetX, 0.001f)
        assertEquals(0f, collapsed.offsetY, 0.001f)
    }

    /** Where a content point `p` (measured from the viewport centre) lands on screen. */
    private fun screenAt(z: ZoomState, p: Float): Float = z.scale * p + z.offsetX

    @Test
    fun `onScaleAt keeps the tapped point exactly where it already was on screen`() {
        // A 1000x1000 viewport: centre 500, pivot 200px right of it.
        val before = ZoomState()
        val after = before.onScaleAt(factor = 2.5f, pivotX = 700f, pivotY = 500f, centerX = 500f, centerY = 500f)
        assertEquals(2.5f, after.scale, 0.001f)
        assertEquals(
            "the content under the finger must not slide towards the viewport centre",
            screenAt(before, 200f),
            screenAt(after, 200f),
            0.001f,
        )
    }

    @Test
    fun `onScaleAt keeps the pivot fixed even when the scale clamps at the max`() {
        val after = ZoomState(scale = 4f).onScaleAt(10f, 700f, 500f, 500f, 500f)
        assertEquals(ZoomState.MAX_SCALE, after.scale, 0.001f)
        assertEquals(4f * 200f, screenAt(after, 200f), 0.001f)
    }

    @Test
    fun `onScaleAt pinching back out to fit returns to identity`() {
        val out = ZoomState().onScaleAt(2.5f, 700f, 500f, 500f, 500f).onScaleAt(1f / 2.5f, 700f, 500f, 500f, 500f)
        assertEquals(1f, out.scale, 0.001f)
        assertEquals(0f, out.offsetX, 0.001f)
    }

    @Test
    fun `toggleAt magnifies a fit image at the tap, and taps back out again`() {
        val tapped = ZoomState().toggleAt(pivotX = 700f, pivotY = 500f, centerX = 500f, centerY = 500f)
        assertEquals(ZoomState.DOUBLE_TAP_SCALE, tapped.scale, 0.001f)
        assertEquals(screenAt(ZoomState(), 200f), screenAt(tapped, 200f), 0.001f)
        // The second double-tap is the way back out — the gesture has to be reversible.
        assertEquals(ZoomState(), tapped.toggleAt(700f, 500f, 500f, 500f))
    }
}
