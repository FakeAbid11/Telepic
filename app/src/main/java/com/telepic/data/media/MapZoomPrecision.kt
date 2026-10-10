package com.telepic.data.media

/**
 * Pure zoom→clustering mapping for the map: the higher the user zooms, the finer the grid cell the
 * [MapClusterer] groups photos into. Precision is the number of decimal places kept on lat/lon —
 * 1 ≈ 11 km, 2 ≈ 1.1 km, 3 ≈ 110 m, 4 ≈ 11 m, 5 ≈ 1 m — so each zoom band swaps clusters of
 * roughly the same *pixel* size on screen. Device-agnostic and unit-tested like the clusterer itself.
 */
object MapZoomPrecision {

    /** Grid used before any zoom event is observed (and the world view's coarse default). */
    const val DEFAULT = 4

    fun forZoom(zoomLevelDouble: Double): Int = when {
        !zoomLevelDouble.isFinite() -> DEFAULT
        zoomLevelDouble < 5.0 -> 1
        zoomLevelDouble < 8.0 -> 2
        zoomLevelDouble < 11.0 -> 3
        zoomLevelDouble < 14.0 -> 4
        else -> 5
    }
}
