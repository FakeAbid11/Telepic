package com.telepic.data.media

import kotlin.math.ln
import kotlin.math.min

/**
 * Pure camera-fit plan for the photo map, computed from the *actual* geotagged coordinates so opening
 * the map frames the user's photos instead of a fixed world view. Testable without osmdroid or a
 * device. Never invents coordinates: an empty set yields no plan and the caller keeps its fallback.
 */
object MapCameraPlan {

    data class Plan(val centerLat: Double, val centerLon: Double, val zoom: Double)

    /**
     * Center = midpoint of the lat/lon bounds. Zoom chosen so the wider span fits a [screenWidthPx]-wide
     * viewport with ~[paddingFactor] margin, clamped to [[minZoom], [maxZoom]]. Identical/single points
     * collapse to [maxZoom]; degenerate (zero) spans never divide by zero.
     */
    fun forPoints(
        coordinates: List<Pair<Double, Double>>,
        screenWidthPx: Int = 1080,
        paddingFactor: Double = 0.75,
        maxZoom: Double = 15.0,
        minZoom: Double = 3.0,
    ): Plan? {
        if (coordinates.isEmpty()) return null
        val lats = coordinates.map { it.first }
        val lons = coordinates.map { it.second }
        val minLat = lats.min(); val maxLat = lats.max()
        val minLon = lons.min(); val maxLon = lons.max()
        val centerLat = (minLat + maxLat) / 2.0
        val centerLon = (minLon + maxLon) / 2.0

        val latSpan = maxLat - minLat
        val lonSpan = maxLon - minLon
        if (latSpan == 0.0 && lonSpan == 0.0) return Plan(centerLat, centerLon, maxZoom)

        // World is 360° lon / ~170.7° lat across `tileSize * 2^z` px. Fit the dominant span.
        val tilePx = 256.0
        val zoomForLon = if (lonSpan > 0.0) ln(360.0 * screenWidthPx * paddingFactor / (tilePx * lonSpan)) / ln(2.0) else maxZoom
        val zoomForLat = if (latSpan > 0.0) ln(170.7 * screenWidthPx * paddingFactor / (tilePx * latSpan)) / ln(2.0) else maxZoom
        val zoom = min(zoomForLon, zoomForLat).coerceIn(minZoom, maxZoom)
        return Plan(centerLat, centerLon, zoom)
    }
}
