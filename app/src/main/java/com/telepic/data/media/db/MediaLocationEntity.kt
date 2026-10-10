package com.telepic.data.media.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached GPS position for one geotagged local item (Phase 10 map). Storing the extracted coordinate
 * (and the content URI needed to render the marker / open the viewer) means the map never re-reads
 * full-resolution EXIF on every render — extraction happens once per item. Items without GPS are not
 * stored, so they never gain a fabricated pin.
 */
@Entity(tableName = "media_location")
data class MediaLocationEntity(
    @PrimaryKey val localMediaId: Long,
    val latitude: Double,
    val longitude: Double,
    val contentUri: String,
    val extractedAt: Long,
)
