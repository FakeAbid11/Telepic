package com.telepix.data.cloud

import com.telepix.data.cloud.db.CloudMediaManifestEntity
import com.telepix.domain.cloud.CloudMedia
import com.telepix.domain.cloud.CloudMediaType

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
        createdAt = nowMillis,
        updatedAt = nowMillis,
    )

    fun CloudMediaManifestEntity.toDomain(): CloudMedia = CloudMedia(
        messageId = messageId,
        chatId = chatId,
        mediaType = runCatching { CloudMediaType.valueOf(mediaType) }.getOrDefault(CloudMediaType.IMAGE),
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
    )
}
