package com.telepic.data.media

import com.telepic.domain.media.GeoLocation

/**
 * Pure EXIF GPS → decimal coordinate math, free of Android so it is unit-testable. EXIF stores
 * latitude/longitude as degrees/minutes/seconds rationals plus a hemisphere reference; the decimal
 * value is `deg + min/60 + sec/3600`, negated for South and West. Malformed, missing or non-finite
 * components yield `null` — Telepic never infers a position for media that has none.
 */
object GpsCoordinates {

    fun toDecimal(degrees: Double, minutes: Double, seconds: Double, reference: String?): Double? {
        if (!degrees.isFinite() || !minutes.isFinite() || !seconds.isFinite()) return null
        if (minutes !in 0.0..60.0 || seconds !in 0.0..60.0) return null
        val sign = when (reference?.trim()?.uppercase()) {
            "N", "E" -> 1.0
            "S", "W" -> -1.0
            else -> return null
        }
        return (degrees + minutes / 60.0 + seconds / 3600.0) * sign
    }

    /**
     * Builds a validated [GeoLocation] from raw EXIF components, or `null` when any part is missing,
     * non-finite, or out of range. This is the single gate that guarantees no fabricated marker.
     */
    fun location(
        latitudeDegrees: Double, latitudeMinutes: Double, latitudeSeconds: Double, latitudeRef: String?,
        longitudeDegrees: Double, longitudeMinutes: Double, longitudeSeconds: Double, longitudeRef: String?,
    ): GeoLocation? {
        val lat = toDecimal(latitudeDegrees, latitudeMinutes, latitudeSeconds, latitudeRef) ?: return null
        val lon = toDecimal(longitudeDegrees, longitudeMinutes, longitudeSeconds, longitudeRef) ?: return null
        val location = GeoLocation(lat, lon)
        return if (location.isValid) location else null
    }
}
