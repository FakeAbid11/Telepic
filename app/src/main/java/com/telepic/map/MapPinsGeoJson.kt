package com.telepic.map

import com.telepic.data.media.MapClusterer
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Turns a clustering pass into the GeoJSON the map's symbol layers draw.
 *
 * MapLibre is style-driven: instead of one marker object per photo, the pins become features in a
 * single source, and two layers (one per [KIND_SINGLE] / [KIND_CLUSTER]) decide how each is drawn.
 * The feature's properties are therefore the only thing that carries a pin's identity across that
 * boundary — `mediaId` for a single photo, `count` for a cluster — so they are named constants here
 * rather than string literals scattered through the layer and click code.
 *
 * Pure by design: the geojson artifact has no Android imports, so the whole mapping is testable with
 * plain JUnit. That matters most for [Point.fromLngLat], which takes **longitude first** — the
 * classic swap that would silently move every pin to the wrong hemisphere.
 */
object MapPinsGeoJson {

    /** Feature property: which layer should draw this pin. */
    const val PROP_KIND = "kind"

    /** Feature property: the id of the image MapLibre should draw for this pin. */
    const val PROP_ICON = "icon"

    /** Feature property: how many photos a cluster stands for. Clusters only. */
    const val PROP_COUNT = "count"

    /** Feature property: the MediaStore id behind a single pin. Singles only. */
    const val PROP_MEDIA_ID = "mediaId"

    const val KIND_SINGLE = "single"
    const val KIND_CLUSTER = "cluster"

    fun featureCollection(pins: List<MapClusterer.MapPin>): FeatureCollection =
        FeatureCollection.fromFeatures(pins.map(::feature))

    private fun feature(pin: MapClusterer.MapPin): Feature {
        // Longitude first. See the class doc.
        val feature = Feature.fromGeometry(Point.fromLngLat(pin.longitude, pin.latitude))
        when (pin) {
            is MapClusterer.MapPin.Single -> {
                feature.addStringProperty(PROP_KIND, KIND_SINGLE)
                feature.addStringProperty(PROP_ICON, MapPinIcons.SINGLE)
                // As a string, not a number: a media id is a Long, and GeoJSON numbers are doubles,
                // so a value past 2^53 would come back changed. Ids are small today; this costs
                // nothing and removes the class of bug.
                feature.addStringProperty(PROP_MEDIA_ID, pin.mediaId.toString())
            }
            is MapClusterer.MapPin.Cluster -> {
                feature.addStringProperty(PROP_KIND, KIND_CLUSTER)
                feature.addStringProperty(PROP_ICON, MapPinIcons.cluster(pin.size))
                feature.addNumberProperty(PROP_COUNT, pin.size)
            }
        }
        return feature
    }
}
