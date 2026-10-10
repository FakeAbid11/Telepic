package com.telepic.domain.media

import android.net.Uri

/**
 * Domain model for a single local media item, normalized from MediaStore.
 *
 * Deliberately free of Cursor / MediaStore column details and filesystem paths: identity is the
 * content URI + MediaStore id. A future content hash (recognition/dedup phase) can be associated
 * by id, but this phase intentionally stores none.
 */
data class LocalMedia(
    val id: Long,
    val contentUri: Uri,
    val type: MediaType,
    val mimeType: String?,
    val displayName: String?,
    /** Capture (or, as a fallback, modification) time, normalized to epoch milliseconds. */
    val dateMillis: Long,
    /** Playback duration in milliseconds for video; null for photos/GIFs. */
    val durationMillis: Long?,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    /** Bucket (folder) identity, backing future albums / folder-scoped backup. */
    val bucketId: Long?,
    val bucketName: String?,
    val relativePath: String?,
) {
    val isVideo: Boolean get() = type == MediaType.VIDEO
    val isGif: Boolean get() = type == MediaType.GIF
}
