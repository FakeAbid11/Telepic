package com.telepix.domain.cloud

/**
 * The neutral "here is a local file, put it in the cloud" request the backup engine hands to
 * [com.telepix.data.cloud.CloudRepository]. It deliberately carries no TDLib request shapes — the
 * engine must not know Telegram internals; the data source maps these fields to `TdApi` types.
 *
 * [stagedPath] points at an app-private staging file the engine already prepared (or the original
 * content path when no copy was needed). The data source owns reading it.
 */
data class CloudUploadRequest(
    val stagedPath: String,
    val fileName: String?,
    val mimeType: String?,
    val mediaType: CloudMediaType,
    val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val dateEpochSec: Long?,
)

/** Byte-level upload progress reported by the data source, when Telegram provides it. */
data class CloudUploadProgress(
    val uploadedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float?
        get() = if (totalBytes > 0L) (uploadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else null
}

/**
 * Telegram's confirmation that the media was accepted, carrying the stable remote identity
 * (`chatId` + `messageId`) the manifest and queue persist. This is the only thing that lets a
 * queue item become BACKED_UP — success is never assumed without it.
 */
data class CloudUploadResult(
    val chatId: Long,
    val messageId: Long,
    val telegramFileId: Int?,
    val mediaType: CloudMediaType,
)
