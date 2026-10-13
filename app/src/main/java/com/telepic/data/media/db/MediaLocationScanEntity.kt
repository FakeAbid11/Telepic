package com.telepic.data.media.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records that one local item has already been examined for embedded GPS — **including items that
 * carry none**.
 *
 * [MediaLocationEntity] deliberately stores only items that *have* a coordinate, so it can never
 * answer "have we already looked at this file?". Without that answer the scan had to re-read EXIF for
 * every coordinate-less item on every open, and because a library's screenshots and downloads
 * overwhelmingly carry no GPS, that meant re-reading the same thousands of files forever. Worse, the
 * scan stops after a bounded number of pages, so the budget was spent re-reading the same prefix and
 * a genuinely geotagged photo further back was never reached at all.
 *
 * This table is the missing memory: one row per examined item, whether or not a coordinate came out
 * of it. A sweep then costs one EXIF read per item *once*, and every later sweep walks past the
 * already-examined items for free.
 */
@Entity(tableName = "media_location_scan")
data class MediaLocationScanEntity(
    @PrimaryKey val localMediaId: Long,
    val scannedAt: Long,
)
