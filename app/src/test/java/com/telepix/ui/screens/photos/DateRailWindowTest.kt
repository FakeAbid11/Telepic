package com.telepix.ui.screens.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the P1 date-rail fix: anchors are newest-first, so the rail must default to the *recent*
 * window and always keep the active day visible — never regress to the oldest slice.
 *
 * [DateRailWindow.window] returns an **inclusive** [IntRange] (`first`..`last`); callers add +1 when
 * feeding it to [List.subList]. These tests assert that inclusive contract directly.
 */
class DateRailWindowTest {

    private val size = 12

    @Test
    fun `empty anchor list yields an empty window`() {
        val window = DateRailWindow.window(anchorCount = 0, activeIndex = 0, windowSize = size)
        assertTrue(window.isEmpty())
    }

    @Test
    fun `defaults to the newest window when nothing is scrolled deep`() {
        val window = DateRailWindow.window(anchorCount = 40, activeIndex = 0, windowSize = size)
        assertEquals(0, window.first)
        assertEquals(size - 1, window.last) // inclusive: newest 12 days are indices 0..11
    }

    @Test
    fun `smaller than the window shows every anchor`() {
        val window = DateRailWindow.window(anchorCount = 5, activeIndex = 3, windowSize = size)
        assertEquals(0, window.first)
        assertEquals(4, window.last)
    }

    @Test
    fun `active day is always inside the window as the user scrolls into older dates`() {
        for (active in 0 until 60) {
            val window = DateRailWindow.window(anchorCount = 60, activeIndex = active, windowSize = size)
            assertTrue("active $active not in $window", active in window)
            assertTrue("window oversized", (window.last - window.first + 1) <= size)
        }
    }

    @Test
    fun `window slides to the tail but never past the last anchor`() {
        val window = DateRailWindow.window(anchorCount = 60, activeIndex = 59, windowSize = size)
        assertEquals(60 - size, window.first)
        assertEquals(59, window.last)
        assertTrue(59 in window) // the active (oldest visible) day must not be dropped
    }

    @Test
    fun `out-of-range active index is coerced into the newest window`() {
        val window = DateRailWindow.window(anchorCount = 20, activeIndex = 999, windowSize = size)
        assertEquals(20 - size, window.first)
        assertTrue(DateRailWindow.includesActive(20, 999, size))
    }

    @Test
    fun `includesActive is false for an empty list`() {
        assertFalse(DateRailWindow.includesActive(anchorCount = 0, activeIndex = 0, windowSize = size))
    }
}
