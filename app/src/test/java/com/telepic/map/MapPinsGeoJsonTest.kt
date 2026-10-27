package com.telepic.map

import com.telepic.data.media.MapClusterer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

/**
 * The bridge from a clustering pass to what the map draws.
 *
 * This is the seam where a pin's identity stops being an object and becomes a set of JSON
 * properties, so it is worth testing for two reasons. The first is the coordinate order: GeoJSON is
 * `[longitude, latitude]`, and swapping them puts every photo in the wrong place with no error
 * anywhere — the map just shows the wrong continent. The second is that a property the layers or the
 * click handler read by name has to actually be there, or the pin silently stops being tappable.
 *
 * Pure JUnit: the geojson artifact has no Android dependencies, so no Robolectric is needed.
 */
class MapPinsGeoJsonTest {

    private fun features(pins: List<MapClusterer.MapPin>): List<Feature> =
        // `features()` is a platform type that MapLibre declares nullable, so `orEmpty()` rather
        // than a `!!` — an empty collection and a missing one mean the same thing to a test.
        MapPinsGeoJson.featureCollection(pins).features().orEmpty()

    @Test
    fun `every pin becomes exactly one feature`() {
        val result = features(
            listOf(
                MapClusterer.MapPin.Single(48.85, 2.35, mediaId = 7L),
                MapClusterer.MapPin.Cluster(51.5, -0.12, ids = listOf(1L, 2L, 3L)),
            ),
        )
        assertEquals(2, result.size)
    }

    @Test
    fun `an empty pin list produces an empty collection`() {
        // The map renders an empty source before the first scan rather than skipping the source, so
        // this path runs on every cold open.
        assertTrue(features(emptyList()).isEmpty())
    }

    @Test
    fun `longitude comes before latitude`() {
        val point = features(listOf(MapClusterer.MapPin.Single(48.85, 2.35, mediaId = 7L)))
            .single()
            .geometry() as Point

        assertEquals("longitude", 2.35, point.longitude(), 1e-9)
        assertEquals("latitude", 48.85, point.latitude(), 1e-9)
    }

    @Test
    fun `a single pin carries its kind, its icon and its media id`() {
        val feature = features(listOf(MapClusterer.MapPin.Single(48.85, 2.35, mediaId = 7L))).single()

        assertEquals(MapPinsGeoJson.KIND_SINGLE, feature.getStringProperty(MapPinsGeoJson.PROP_KIND))
        assertEquals(MapPinIcons.SINGLE, feature.getStringProperty(MapPinsGeoJson.PROP_ICON))
        // As a string: the click handler parses it back to a Long, and a JSON number would be a
        // double on the way out.
        assertEquals("7", feature.getStringProperty(MapPinsGeoJson.PROP_MEDIA_ID))
        assertNull(
            "a single pin is not a cluster and must not claim a count",
            feature.getNumberProperty(MapPinsGeoJson.PROP_COUNT),
        )
    }

    @Test
    fun `a cluster carries its size and its own icon`() {
        val feature = features(
            listOf(MapClusterer.MapPin.Cluster(51.5, -0.12, ids = listOf(1L, 2L, 3L))),
        ).single()

        assertEquals(MapPinsGeoJson.KIND_CLUSTER, feature.getStringProperty(MapPinsGeoJson.PROP_KIND))
        assertEquals(MapPinIcons.cluster(3), feature.getStringProperty(MapPinsGeoJson.PROP_ICON))
        assertEquals(3, feature.getNumberProperty(MapPinsGeoJson.PROP_COUNT).toInt())
        assertNull(
            "a cluster is not a single photo and must not claim a media id",
            feature.getStringProperty(MapPinsGeoJson.PROP_MEDIA_ID),
        )
    }

    @Test
    fun `the two kinds are distinguishable by their kind property alone`() {
        // Both layers filter on this one property, so a value that did not match either filter would
        // produce a feature nothing draws — a photo that exists on the map and cannot be seen.
        val result = features(
            listOf(
                MapClusterer.MapPin.Single(1.0, 2.0, mediaId = 1L),
                MapClusterer.MapPin.Cluster(3.0, 4.0, ids = listOf(2L, 3L)),
            ),
        )
        val kinds = result.map { it.getStringProperty(MapPinsGeoJson.PROP_KIND) }
        assertEquals(
            listOf(MapPinsGeoJson.KIND_SINGLE, MapPinsGeoJson.KIND_CLUSTER),
            kinds,
        )
    }
}
