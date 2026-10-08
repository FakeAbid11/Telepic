package com.telepix.data.media

import android.net.Uri
import com.telepix.domain.media.Album

/**
 * Pure MediaStore-bucket grouping, independent of any cursor so it can be unit-tested. Given rows
 * already ordered newest-first, the first row per bucket is its cover; identity is the bucket id and
 * the display name is taken from the provider (with a neutral fallback), never from a filename.
 */
object AlbumGrouper {

    /** One grouped query row reduced to just what album building needs. */
    data class Row(
        val id: Long,
        val bucketId: Long?,
        val bucketName: String?,
        val mimeType: String?,
        val whenMillis: Long,
    )

    fun group(rows: List<Row>, coverUriFor: (id: Long, mimeType: String?) -> Uri): List<Album> {
        val counts = LinkedHashMap<Long, Int>()
        val covers = HashMap<Long, Row>()
        val titles = HashMap<Long, String>()
        for (row in rows) {
            val bucket = row.bucketId ?: continue
            counts[bucket] = (counts[bucket] ?: 0) + 1
            // Rows are newest-first, so the first row seen for a bucket is its cover.
            covers.putIfAbsent(bucket, row)
            row.bucketName?.takeIf { it.isNotBlank() }?.let { titles.putIfAbsent(bucket, it) }
        }
        return covers.mapNotNull { (bucket, cover) ->
            Album(
                bucketId = bucket,
                title = titles[bucket] ?: UNTITLED,
                coverUri = coverUriFor(cover.id, cover.mimeType),
                coverIsVideo = cover.mimeType?.startsWith("video/") == true,
                count = counts[bucket] ?: 0,
                latestMillis = cover.whenMillis,
            )
        }.sortedByDescending { it.latestMillis }
    }

    private const val UNTITLED = "Album"
}
