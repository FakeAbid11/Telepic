package com.telepic.ui.screens.viewer

import com.telepic.navigation.MediaSource

/**
 * Pure swipe-to-page decision for the Viewer: converts a completed horizontal drag into the adjacent
 * [MediaSource] to open, using the neighbor sources already resolved on the current state. A drag
 * left (negative delta) moves to the older item ([next]); a drag right moves to the newer one
 * ([previous]) — matching the arrow-button semantics in the bottom bar. Drags under the threshold,
 * at the ends of the list, or on a zero-width container resolve to null (spring back).
 */
object SwipeResolver {

    /** Fraction of the viewport a horizontal drag must cross to commit a page change. */
    const val THRESHOLD_FRACTION = 0.25f

    fun resolve(
        deltaPx: Float,
        widthPx: Float,
        previous: MediaSource?,
        next: MediaSource?,
    ): MediaSource? {
        if (widthPx <= 0f) return null
        val threshold = widthPx * THRESHOLD_FRACTION
        return when {
            deltaPx <= -threshold -> next
            deltaPx >= threshold -> previous
            else -> null
        }
    }
}
