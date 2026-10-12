package com.telepic.navigation

import android.net.Uri

/**
 * Navigation contract for an album's contents.
 *
 * Two shapes, because the two kinds of album genuinely differ. A **folder** album carries the stable
 * MediaStore [ARG_BUCKET_ID] plus the display title (encoded) — its identity is the bucket, never the
 * title. A **user-created** album carries only [ARG_ALBUM_ID], because its name is editable and is
 * observed from the database instead of being frozen into the route: encoding a name into a route
 * would leave the header showing a stale title after a rename.
 *
 * The custom base is a *different first segment* (`customalbum`) rather than a marker inside the
 * folder path. `album/custom/5` would have three segments, exactly like `album/{bucketId}/{title}`, and
 * would rely on the `LongType` argument rejecting the literal "custom" to disambiguate. Two shapes with
 * different segment counts cannot collide at all.
 */
object AlbumRoute {
    const val BASE = "album"
    const val ARG_BUCKET_ID = "bucketId"
    const val ARG_TITLE = "title"
    const val PATTERN = "$BASE/{$ARG_BUCKET_ID}/{$ARG_TITLE}"

    const val CUSTOM_BASE = "customalbum"
    const val ARG_ALBUM_ID = "albumId"
    const val CUSTOM_PATTERN = "$CUSTOM_BASE/{$ARG_ALBUM_ID}"

    fun create(bucketId: Long, title: String): String =
        "$BASE/$bucketId/${Uri.encode(title)}"

    fun createCustom(albumId: Long): String = "$CUSTOM_BASE/$albumId"
}

/**
 * Which album a contents screen is showing, and what identifies it.
 *
 * Carried as a type rather than a raw id so the two identity spaces can never be confused: a bucket id
 * and a custom album id are both `Long`, and passing one where the other was meant would silently open
 * the wrong — or an empty — album.
 */
sealed interface AlbumSource {
    /** Used as the header title until (or unless) a stored name supersedes it. */
    val title: String

    /** A device folder album, identified by its MediaStore bucket. */
    data class Bucket(val bucketId: Long, override val title: String) : AlbumSource

    /** A user-created album, identified by its Room row. */
    data class Custom(val albumId: Long, override val title: String) : AlbumSource
}
