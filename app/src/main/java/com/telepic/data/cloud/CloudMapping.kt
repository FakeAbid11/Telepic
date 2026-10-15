package com.telepic.data.cloud

import com.telepic.data.cloud.db.CloudMediaManifestEntity
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudMediaType

/** Conversions between the Room manifest row and the domain [CloudMedia]. Pure and testable. */
object CloudMapping {

    fun CloudMedia.toEntity(nowMillis: Long): CloudMediaManifestEntity = CloudMediaManifestEntity(
        chatId = chatId,
        messageId = messageId,
        mediaType = mediaType.name,
        mimeType = mimeType,
        fileName = fileName,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        durationMs = durationMs,
        dateEpochSec = dateEpochSec,
        previewFileId = previewFileId,
        originalFileId = originalFileId,
        isDownloaded = isDownloaded,
        contentHash = contentHash,
        contentSizeBytes = contentSizeBytes,
        createdAt = nowMillis,
        updatedAt = nowMillis,
    )

    fun CloudMediaManifestEntity.toDomain(): CloudMedia = CloudMedia(
        messageId = messageId,
        chatId = chatId,
        mediaType = runCatching { CloudMediaType.valueOf(mediaType) }.getOrElse {
            // A corrupted/unknown value must not masquerade as a valid photo without a trace: log the
            // fallback so a bad migration or a manual DB edit is visible instead of silent.
            TelepicLog.warn("CloudMapping", "unknown media type '$mediaType'; defaulting to IMAGE", it)
            CloudMediaType.IMAGE
        },
        mimeType = mimeType,
        fileName = fileName,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        durationMs = durationMs,
        dateEpochSec = dateEpochSec,
        previewFileId = previewFileId,
        originalFileId = originalFileId,
        isDownloaded = isDownloaded,
        contentHash = contentHash,
        contentSizeBytes = contentSizeBytes,
    )
}
