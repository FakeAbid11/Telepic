package com.telepic.data.media

/**
 * Pure zoom→clustering mapping for the map: the higher the user zooms, the finer the grid cell the
 * [MapClusterer] groups photos into. Precision is the number of decimal places kept on lat/lon —
 * 1 ≈ 11 km, 2 ≈ 1.1 km, 3 ≈ 110 m, 4 ≈ 11 m, 5 ≈ 1 m — so each zoom band swaps clusters of
 * roughly the same *pixel* size on screen. Device-agnostic and unit-tested like the clusterer itself.
 *
 * The band edges are one lower than they were before the engine changed, and that is the point: the
 * bands are chosen for *pixel* size, and MapLibre's zoom is defined on a 512 px world grid where the
 * raster engine's was 256. Keeping the old numbers would have made every band fire at half the
 * previous scale, so the same cluster appeared twice as dense.
 */
object MapZoomPrecision {

    /** Grid used before any zoom event is observed (and the world view's coarse default). */
    const val DEFAULT = 4

    fun forZoom(zoomLevelDouble: Double): Int = when {
        !zoomLevelDouble.isFinite() -> DEFAULT
        zoomLevelDouble < 4.0 -> 1
        zoomLevelDouble < 7.0 -> 2
        zoomLevelDouble < 10.0 -> 3
        zoomLevelDouble < 13.0 -> 4
        else -> 5
    }
}
