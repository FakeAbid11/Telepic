package com.telepix.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the P1 map-camera fix: the camera is framed from the *actual* coordinates, and a missing
 * or degenerate coordinate set yields no plan (the caller keeps a neutral view) — never a fabricated
 * center like (0,0).
 */
class MapCameraPlanTest {

    private fun near(expected: Double, actual: Double) = assertTrue(
        "expected $expected got $actual",
        kotlin.math.abs(expected - actual) < 1e-6,
    )

    @Test
    fun `empty coordinates produce no plan`() {
        assertNull(MapCameraPlan.forPoints(emptyList()))
    }

    @Test
    fun `a single point centers on it at the max zoom`() {
        val plan = MapCameraPlan.forPoints(listOf(48.85 to 2.35))
        assertNotNull(plan)
        near(48.85, plan!!.centerLat)
        near(2.35, plan.centerLon)
        assertEquals(plan.zoom, 15.0, 1e-6)
    }

    @Test
    fun `identical points do not divide by zero and collapse to max zoom`() {
        val plan = MapCameraPlan.forPoints(List(5) { 10.0 to 20.0 })
        assertNotNull(plan)
        near(10.0, plan!!.centerLat)
        near(20.0, plan.centerLon)
        assertEquals(15.0, plan.zoom, 1e-6)
    }

    @Test
    fun `spread points center on the bounds midpoint`() {
        val plan = MapCameraPlan.forPoints(listOf(0.0 to 0.0, 10.0 to 10.0))
        assertNotNull(plan)
        near(5.0, plan!!.centerLat)
        near(5.0, plan.centerLon)
    }

    @Test
    fun `zoom stays within the configured bounds for any spread`() {
        val wide = MapCameraPlan.forPoints(listOf(-80.0 to -170.0, 80.0 to 170.0), minZoom = 3.0, maxZoom = 15.0)!!
        assertTrue(wide.zoom >= 3.0 && wide.zoom <= 15.0)
        val tight = MapCameraPlan.forPoints(listOf(0.0 to 0.0, 0.0001 to 0.0001), minZoom = 3.0, maxZoom = 15.0)!!
        assertTrue(tight.zoom >= 3.0 && tight.zoom <= 15.0)
    }

    @Test
    fun `never invents a default world center when real points exist`() {
        val plan = MapCameraPlan.forPoints(listOf(35.65 to 139.83, 35.70 to 139.90))!!
        // The old bug pinned (20.0, 0.0); the fit must reflect Tokyo, not a fabricated center.
        assertTrue(plan.centerLat > 30.0 && plan.centerLon > 130.0)
    }
}
