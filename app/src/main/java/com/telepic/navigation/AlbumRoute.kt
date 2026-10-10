package com.telepic.navigation

/**
 * Navigation contract for an album's contents. Carries the stable MediaStore [ARG_BUCKET_ID] plus the
 * display title (encoded); the album's identity is the bucket id, never the title.
 */
object AlbumRoute {
    const val BASE = "album"
    const val ARG_BUCKET_ID = "bucketId"
    const val ARG_TITLE = "title"
    const val PATTERN = "$BASE/{$ARG_BUCKET_ID}/{$ARG_TITLE}"

    fun create(bucketId: Long, title: String): String =
        "$BASE/$bucketId/${android.net.Uri.encode(title)}"
}
