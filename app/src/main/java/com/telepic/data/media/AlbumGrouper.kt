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

    fun group(
        rows: List<Row>,
        /**
         * Name for a bucket the provider gave no display name for. Passed in rather than hard-coded so
         * it stays translatable — it is the only user-visible string this otherwise-pure object emits.
         */
        untitled: String = "Album",
        coverUriFor: (id: Long, mimeType: String?) -> Uri,
    ): List<Album> {
        val accumulator = Accumulator(untitled, coverUriFor)
        rows.forEach(accumulator::add)
        return accumulator.build()
    }

    /**
     * Streaming form of [group]: the same grouping, accumulated as rows arrive, so a caller reading a
     * MediaStore cursor never has to materialise the whole library first.
     *
     * [group] only needs the complete list because it takes one. The grouping itself keeps four maps
     * keyed by bucket, so its memory is proportional to the number of **albums**, not the number of
     * media items — which is what lets `MediaStoreAlbumRepository.albums` hold a few dozen entries on
     * a 50,000-item library instead of 50,000 [Row] objects.
     */
    class Accumulator(
        private val untitled: String,
        private val coverUriFor: (id: Long, mimeType: String?) -> Uri,
    ) {
        private val counts = LinkedHashMap<Long, Int>()
        private val covers = HashMap<Long, Row>()
        private val titles = HashMap<Long, String>()
        private val paths = HashMap<Long, String>()

        /**
         * Rows must arrive newest-first, exactly as [group] requires: the first row seen for a bucket
         * is its cover, so feeding them in any other order changes which item represents the album.
         */
        fun add(row: Row) {
            val bucket = row.bucketId ?: return
            counts[bucket] = (counts[bucket] ?: 0) + 1
            covers.putIfAbsent(bucket, row)
            row.bucketName?.takeIf { it.isNotBlank() }?.let { titles.putIfAbsent(bucket, it) }
            row.relativePath?.takeIf { it.isNotBlank() }?.let { paths.putIfAbsent(bucket, it) }
        }

        fun build(): List<Album> = covers.mapNotNull { (bucket, cover) ->
            Album(
                bucketId = bucket,
                title = titles[bucket] ?: untitled,
                coverUri = coverUriFor(cover.id, cover.mimeType),
                coverIsVideo = cover.mimeType?.startsWith("video/") == true,
                count = counts[bucket] ?: 0,
                latestMillis = cover.whenMillis,
                relativePath = paths[bucket],
            )
        }.sortedByDescending { it.latestMillis }
    }
}
