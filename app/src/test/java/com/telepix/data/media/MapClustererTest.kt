package com.telepix.data.media

import com.telepix.domain.media.GeotaggedMedia
import com.telepix.domain.media.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Media → marker mapping and nearby clustering: preserves identity, groups dense points, never drops items. */
class MapClustererTest {

    private fun tagged(id: Long, lat: Double, lon: Double) =
        GeotaggedMedia(id, GeoLocation(lat, lon), "content://m/$id")

    @Test
    fun `isolated points stay single pins carrying their own id`() {
        val pins = MapClusterer.cluster(
            listOf(tagged(1, 48.85, 2.35), tagged(2, 40.71, -74.0)),
            precision = 4,
        )
        assertEquals(2, pins.size)
        assertTrue(pins.all { it is MapClusterer.MapPin.Single })
        assertEquals(setOf(1L, 2L), pins.map { it.ids.single() }.toSet())
    }

    @Test
    fun `nearby points in the same grid cell collapse into one cluster with all ids`() {
        val near = listOf(
            tagged(10, 48.850001, 2.350001),
            tagged(11, 48.850002, 2.350002),
            tagged(12, 48.850003, 2.350003),
        )
        val pins = MapClusterer.cluster(near, precision = 4)
        assertEquals(1, pins.size)
        val cluster = pins.single() as MapClusterer.MapPin.Cluster
        assertEquals(3, cluster.size)
        assertEquals(listOf(10L, 11L, 12L), cluster.ids) // sorted, full membership preserved
    }

    @Test
    fun `no item is lost when grouping`() {
        val items = (1L..50L).map { tagged(it, 1.0 + it / 1_000_000.0, 2.0) }
        val pins = MapClusterer.cluster(items, precision = 5)
        assertEquals(50, pins.sumOf { it.size })
    }

    @Test
    fun `a coarse precision groups more aggressively than a fine one`() {
        val items = listOf(tagged(1, 10.0001, 10.0001), tagged(2, 10.0009, 10.0009))
        assertEquals(1, MapClusterer.cluster(items, precision = 2).size) // coarse -> same cell
        assertEquals(2, MapClusterer.cluster(items, precision = 8).size) // fine -> distinct
    }

    @Test
    fun `empty input yields no pins`() {
        assertTrue(MapClusterer.cluster(emptyList(), precision = 4).isEmpty())
    }
}
