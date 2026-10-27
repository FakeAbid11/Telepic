package com.telepic.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The zoom→precision mapping must be monotonic (never coarser when zooming in), bounded to the
 * grid precisions the clusterer supports, and honest about degenerate zoom values.
 *
 * The numbers are MapLibre zoom, one lower than the raster engine's for the same on-screen scale
 * (512 px world grid vs 256). The bands are chosen for *pixel* size, so they move with that grid
 * rather than staying put — pinning them here is what makes that deliberate instead of accidental.
 */
class MapZoomPrecisionTest {

    @Test
    fun `world zooms cluster coarsely`() {
        assertEquals(1, MapZoomPrecision.forZoom(2.0))
        assertEquals(1, MapZoomPrecision.forZoom(3.9))
    }

    @Test
    fun `each zoom band is at least as fine as the last`() {
        val bands = listOf(3.0, 5.0, 8.0, 11.0, 14.0, 19.0).map { MapZoomPrecision.forZoom(it) }
        assertEquals(listOf(1, 2, 3, 4, 5, 5), bands)
    }

    @Test
    fun `band edges pick the finer grid exactly at the threshold`() {
        assertEquals(2, MapZoomPrecision.forZoom(4.0))
        assertEquals(3, MapZoomPrecision.forZoom(7.0))
        assertEquals(4, MapZoomPrecision.forZoom(10.0))
        assertEquals(5, MapZoomPrecision.forZoom(13.0))
    }

    @Test
    fun `degenerate zoom values fall back to the default grid`() {
        assertEquals(MapZoomPrecision.DEFAULT, MapZoomPrecision.forZoom(Double.NaN))
        assertEquals(MapZoomPrecision.DEFAULT, MapZoomPrecision.forZoom(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `the default grid matches the mid-city band used before any zoom event`() {
        assertEquals(4, MapZoomPrecision.DEFAULT)
    }
}
