package com.telepic.domain.media

import android.net.Uri

/**
 * A local album / folder derived from MediaStore buckets. Identity is the stable `bucketId` (never
 * the display name); [coverUri] is the newest item's content URI and [count] is how many supported
 * media items live in the bucket. No album membership is duplicated on disk.
 */
data class Album(
    val bucketId: Long,
    val title: String,
    val coverUri: Uri,
    val coverIsVideo: Boolean,
    val count: Int,
    val latestMillis: Long,
)
