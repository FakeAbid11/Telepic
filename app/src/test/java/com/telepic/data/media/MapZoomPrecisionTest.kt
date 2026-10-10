package com.telepic.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The zoom→precision mapping must be monotonic (never coarser when zooming in), bounded to the
 * grid precisions the clusterer supports, and honest about degenerate zoom values.
 */
class MapZoomPrecisionTest {

    @Test
    fun `world zooms cluster coarsely`() {
        assertEquals(1, MapZoomPrecision.forZoom(2.0))
        assertEquals(1, MapZoomPrecision.forZoom(4.9))
    }

    @Test
    fun `each zoom band is at least as fine as the last`() {
        val bands = listOf(4.0, 6.0, 9.0, 12.0, 15.0, 19.0).map { MapZoomPrecision.forZoom(it) }
        assertEquals(listOf(1, 2, 3, 4, 5, 5), bands)
    }

    @Test
    fun `band edges pick the finer grid exactly at the threshold`() {
        assertEquals(2, MapZoomPrecision.forZoom(5.0))
        assertEquals(3, MapZoomPrecision.forZoom(8.0))
        assertEquals(4, MapZoomPrecision.forZoom(11.0))
        assertEquals(5, MapZoomPrecision.forZoom(14.0))
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
