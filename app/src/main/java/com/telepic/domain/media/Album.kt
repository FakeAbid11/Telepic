package com.telepic.domain.media

import android.net.Uri
import androidx.compose.runtime.Immutable

/**
 * A local album / folder derived from MediaStore buckets. Identity is the stable `bucketId` (never
 * the display name); [coverUri] is the newest item's content URI and [count] is how many supported
 * media items live in the bucket. No album membership is duplicated on disk.
 *
 * [Immutable] because [coverUri] is a `Uri`, which the compiler cannot prove immutable — without it
 * every album cell in the grid is treated as unstable and recomposes with its parent.
 */
@Immutable
data class Album(
    val bucketId: Long,
    val title: String,
    val coverUri: Uri,
    val coverIsVideo: Boolean,
    val count: Int,
    val latestMillis: Long,
)
