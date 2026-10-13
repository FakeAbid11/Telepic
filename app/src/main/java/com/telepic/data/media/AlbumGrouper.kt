package com.telepic.data.media

import android.net.Uri
import com.telepic.domain.media.Album

/**
 * Pure MediaStore-bucket grouping, independent of any cursor so it can be unit-tested. Given rows
 * already ordered newest-first, the first row per bucket is its cover; identity is the bucket id and
 * the display name is taken from the provider (with a neutral fallback), never from a filename.
 *
 * A bucket's [Row.bucketName] is only its last path segment, so two distinct buckets can carry the
 * same name. [Row.relativePath] is carried through unchanged so a caller that has to *present* the
 * choice (the SELECT_FOLDER picker) can tell them apart without this grouper having to invent a
 * display rule of its own.
 */
object AlbumGrouper {

    /** One grouped query row reduced to just what album building needs. */
    data class Row(
        val id: Long,
        val bucketId: Long?,
        val bucketName: String?,
        val relativePath: String? = null,
        val mimeType: String?,
        val whenMillis: Long,
    )

    fun group(rows: List<Row>, coverUriFor: (id: Long, mimeType: String?) -> Uri): List<Album> {
        val counts = LinkedHashMap<Long, Int>()
        val covers = HashMap<Long, Row>()
        val titles = HashMap<Long, String>()
        val paths = HashMap<Long, String>()
        for (row in rows) {
            val bucket = row.bucketId ?: continue
            counts[bucket] = (counts[bucket] ?: 0) + 1
            // Rows are newest-first, so the first row seen for a bucket is its cover.
            covers.putIfAbsent(bucket, row)
            row.bucketName?.takeIf { it.isNotBlank() }?.let { titles.putIfAbsent(bucket, it) }
            row.relativePath?.takeIf { it.isNotBlank() }?.let { paths.putIfAbsent(bucket, it) }
        }
        return covers.mapNotNull { (bucket, cover) ->
            Album(
                bucketId = bucket,
                title = titles[bucket] ?: UNTITLED,
                coverUri = coverUriFor(cover.id, cover.mimeType),
                coverIsVideo = cover.mimeType?.startsWith("video/") == true,
                count = counts[bucket] ?: 0,
                latestMillis = cover.whenMillis,
                relativePath = paths[bucket],
            )
        }.sortedByDescending { it.latestMillis }
    }

    private const val UNTITLED = "Album"
}
