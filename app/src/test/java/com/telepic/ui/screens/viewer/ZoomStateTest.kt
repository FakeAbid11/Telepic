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
}
