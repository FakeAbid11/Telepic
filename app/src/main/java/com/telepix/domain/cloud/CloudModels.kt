package com.telepix.domain.cloud

/** The kinds of remote media Telepix browses in its Telegram cloud. */
enum class CloudMediaType {
    IMAGE,
    VIDEO,
    GIF,
}

/**
 * The validated Telegram destination that backs Telepix's cloud (the "Telepix Backup" channel).
 *
 * [isValidated] is only true once the destination has been confirmed to be a channel the
 * authenticated account can actually post to — never inferred from the title alone.
 */
data class TelepixCloudDestination(
    val chatId: Long,
    val title: String,
    val isChannel: Boolean,
    val canPostMessages: Boolean,
    val isValidated: Boolean,
)

/**
 * A remote media item discovered in the cloud destination.
 *
 * Stable remote identity is `chatId` + `messageId` — never the filename. Carries metadata and
 * optional file identities; [isDownloaded] reflects whether the *original* is available locally.
 * [contentHash] is reserved for the future recognition/dedup phase (Phase 7) and is never
 * computed in Phase 5.
 */
data class CloudMedia(
    val messageId: Long,
    val chatId: Long,
    val mediaType: CloudMediaType,
    val mimeType: String?,
    val fileName: String?,
    val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val dateEpochSec: Long?,
    val previewFileId: Int?,
    val originalFileId: Int?,
    val isDownloaded: Boolean = false,
    val contentHash: String? = null,
)

/** A downloaded, locally available preview/thumbnail for a [CloudMedia]. */
data class CloudPreview(
    val localPath: String,
    val width: Int?,
    val height: Int?,
)

/** The result of explicitly downloading a cloud original. */
data class LocalDownloadedMedia(
    val localPath: String,
    val chatId: Long,
    val messageId: Long,
)
