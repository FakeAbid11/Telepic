package com.telepix.ui.screens.photos

/**
 * Pure windowing for the Photos date rail. [anchors] are ordered **newest-first**; the rail shows at
 * most [windowSize] entries. It defaults to the newest window so the recent dates are reachable on
 * open, and slides down only as far as needed to keep the [activeIndex] (the day currently at the top
 * of the grid) visible when the user scrolls into older dates. No allocation churn, trivially testable.
 */
object DateRailWindow {

    /** Inclusive-first / exclusive-last index range into a newest-first [anchorCount] list. */
    fun window(anchorCount: Int, activeIndex: Int, windowSize: Int): IntRange {
        if (anchorCount <= 0 || windowSize <= 0) return 0 until 0
        val size = minOf(windowSize, anchorCount)
        val active = activeIndex.coerceIn(0, anchorCount - 1)
        // Prefer the newest window; only slide down enough to include the active (older) day.
        val start = if (active < size) 0 else active - size + 1
        val end = minOf(start + size, anchorCount)
        return start until end
    }

    /** Whether the active day is within the shown window (defensive guard for empty anchors). */
    fun includesActive(anchorCount: Int, activeIndex: Int, windowSize: Int): Boolean {
        if (anchorCount == 0) return false
        val range = window(anchorCount, activeIndex, windowSize)
        return activeIndex.coerceIn(0, anchorCount - 1) in range
    }
}
