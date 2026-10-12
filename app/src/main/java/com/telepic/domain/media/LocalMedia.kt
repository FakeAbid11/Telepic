package com.telepic.domain.media

import android.net.Uri
import androidx.compose.runtime.Immutable

/**
 * Domain model for a single local media item, normalized from MediaStore.
 *
 * Deliberately free of Cursor / MediaStore column details and filesystem paths: identity is the
 * content URI + MediaStore id. A future content hash (recognition/dedup phase) can be associated
 * by id, but this phase intentionally stores none.
 *
 * [Immutable] is a data contract, not a UI hint, and it is load-bearing: without it the compiler
 * infers this class as *unstable*, because [contentUri] is an `android.net.Uri` it cannot prove
 * immutable. An unstable type passed to a composable is compared by identity, so a rebuilt instance
 * invalidates the receiver — which is how one backup-progress tick used to recompose every visible
 * grid tile. Every property is a `val` of an immutable type, so the annotation is truthful.
 */
@Immutable
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
