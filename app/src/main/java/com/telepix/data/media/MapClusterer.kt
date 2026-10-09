package com.telepix.data.media

import com.telepix.domain.media.GeotaggedMedia
import kotlin.math.roundToLong

/**
 * Pure, deterministic marker grouping so dense photo clusters stay performant without a heavy
 * spatial index. Markers that round to the same grid cell (at a zoom-dependent [precision] of
 * decimal places) collapse into one [MapPin.Cluster]; isolated markers stay a [MapPin.Single]. A
 * cluster keeps every member id so tapping it still opens exactly the right media — grouping is a
 * rendering strategy, never a change to item identity.
 */
object MapClusterer {

    sealed interface MapPin {
        val latitude: Double
        val longitude: Double
        val size: Int
        val ids: List<Long>

        data class Single(override val latitude: Double, override val longitude: Double, val mediaId: Long) : MapPin {
            override val size: Int = 1
            override val ids: List<Long> = listOf(mediaId)
        }

        data class Cluster(
            override val latitude: Double,
            override val longitude: Double,
            override val ids: List<Long>,
        ) : MapPin {
            override val size: Int = ids.size
        }
    }

    fun cluster(items: List<GeotaggedMedia>, precision: Int): List<MapPin> {
        if (items.isEmpty()) return emptyList()
        val scale = 10.0.pow(precision.coerceIn(0, 8))
        val groups = LinkedHashMap<Cell, MutableList<GeotaggedMedia>>()
        for (item in items) {
            val cell = Cell(
                (item.location.latitude * scale).roundToLong(),
                (item.location.longitude * scale).roundToLong(),
            )
            groups.getOrPut(cell) { ArrayList() }.add(item)
        }
        return groups.values.map { members ->
            if (members.size == 1) {
                val only = members.first()
                MapPin.Single(only.location.latitude, only.location.longitude, only.mediaId)
            } else {
                val lat = members.sumOf { it.location.latitude } / members.size
                val lon = members.sumOf { it.location.longitude } / members.size
                MapPin.Cluster(lat, lon, members.map { it.mediaId }.sorted())
            }
        }
    }

    private data class Cell(val latCell: Long, val lonCell: Long)
}

private fun Double.pow(exponent: Int): Double {
    var result = 1.0
    repeat(exponent) { result *= this }
    return result
}
