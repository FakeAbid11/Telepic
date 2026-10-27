package com.telepic.map

/**
 * The image ids the map registers with MapLibre's style, and the one place their naming lives.
 *
 * MapLibre draws a marker by looking up a registered image by id, so the id is the link between a
 * feature's properties and the bitmap [com.telepic.ui.screens.map.MapPinBadge] drew for it. Keeping
 * the scheme here rather than inline in the layer expression means the writer and the reader cannot
 * drift, and it makes the naming unit-testable without a map.
 *
 * A cluster id carries its own count because each bubble is drawn with its number on it: two
 * clusters of different sizes need two different images. The set of distinct counts in one clustering
 * pass is small, so the map registers one image per distinct count and removes the rest.
 */
object MapPinIcons {

    /** The single-photo pin. One image for the whole map — it carries no per-item data. */
    const val SINGLE = "telepic-pin"

    /** The count bubble for a cluster of [count] photos. */
    fun cluster(count: Int): String = "telepic-cluster-$count"
}
