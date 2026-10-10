package com.telepic.ui.screens.viewer

import com.telepic.navigation.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Swipe-to-page decisions for the Viewer: a committed horizontal drag maps to the adjacent source
 * (left → older/next, right → newer/previous) only past the threshold, and never to a null neighbor
 * or on a degenerate viewport.
 */
class SwipeResolverTest {

    private val previous = MediaSource.Local(6L)
    private val next = MediaSource.Local(4L)
    private val width = 1000f
    private val threshold = width * SwipeResolver.THRESHOLD_FRACTION

    @Test
    fun `a drag left past the threshold advances to the older item`() {
        assertEquals(next, SwipeResolver.resolve(deltaPx = -threshold, widthPx = width, previous = previous, next = next))
        assertEquals(next, SwipeResolver.resolve(deltaPx = -width, widthPx = width, previous = previous, next = next))
    }

    @Test
    fun `a drag right past the threshold goes back to the newer item`() {
        assertEquals(previous, SwipeResolver.resolve(deltaPx = threshold, widthPx = width, previous = previous, next = next))
    }

    @Test
    fun `a drag under the threshold springs back`() {
        assertNull(SwipeResolver.resolve(deltaPx = -threshold + 1f, widthPx = width, previous = previous, next = next))
        assertNull(SwipeResolver.resolve(deltaPx = threshold - 1f, widthPx = width, previous = previous, next = next))
        assertNull(SwipeResolver.resolve(deltaPx = 0f, widthPx = width, previous = previous, next = next))
    }

    @Test
    fun `at the ends of the list a committed swipe has no target`() {
        assertNull(SwipeResolver.resolve(deltaPx = -width, widthPx = width, previous = previous, next = null))
        assertNull(SwipeResolver.resolve(deltaPx = width, widthPx = width, previous = null, next = next))
    }

    @Test
    fun `a zero-width container never commits a swipe`() {
        assertNull(SwipeResolver.resolve(deltaPx = -9999f, widthPx = 0f, previous = previous, next = next))
        assertNull(SwipeResolver.resolve(deltaPx = -9999f, widthPx = -1f, previous = previous, next = next))
    }
}
