package com.telepic.domain.media

/**
 * A WGS-84 coordinate read from a media file. Telepic derives these ONLY from embedded EXIF GPS
 * (never filenames, albums, network or device location) and never uploads them. An item without a
 * valid [GeoLocation] simply does not appear on the map — no location is ever fabricated.
 */
data class GeoLocation(val latitude: Double, val longitude: Double) {
    val isValid: Boolean
        get() = latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}

/** A geotagged local item: its stable MediaStore id, its location, and its content URI. */
data class GeotaggedMedia(val mediaId: Long, val location: GeoLocation, val contentUri: String)
