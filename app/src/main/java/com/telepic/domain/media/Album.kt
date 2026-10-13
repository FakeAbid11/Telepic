package com.telepic.domain.media

import android.net.Uri
import androidx.compose.runtime.Immutable

/**
 * A local album / folder derived from MediaStore buckets. Identity is the stable `bucketId` (never
 * the display name); [coverUri] is the newest item's content URI and [count] is how many supported
 * media items live in the bucket. No album membership is duplicated on disk.
 *
 * [relativePath] is the folder's path inside its volume (`DCIM/Camera/`), and it exists because
 * [title] is the *last path segment* — so `DCIM/Camera` and `Pictures/Camera` are two different
 * buckets that both present themselves as "Camera". Nothing about identity changes here: the
 * `bucketId` was always the real key, and the path is what lets a picker show the user which of two
 * same-named folders they are actually choosing. It is nullable because the column only exists from
 * API 29; on older releases the caller must fall back to the name alone.
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
    val relativePath: String? = null,
)
