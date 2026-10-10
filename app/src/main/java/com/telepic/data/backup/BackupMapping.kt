package com.telepic.data.backup

import com.telepic.data.backup.db.BackupQueueEntity
import com.telepic.domain.backup.BackupItem
import com.telepic.domain.backup.BackupQueueStats
import com.telepic.domain.backup.BackupState
import com.telepic.domain.cloud.CloudMediaType
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType

/** Pure, testable conversions between the Room queue row, the domain [BackupItem], and [LocalMedia]. */
object BackupMapping {

    fun BackupQueueEntity.toDomain(): BackupItem = BackupItem(
        id = id,
        localMediaId = localMediaId,
        contentUri = contentUri,
        mediaType = mediaType,
        mimeType = mimeType,
        fileName = fileName,
        sizeBytes = sizeBytes,
        modifiedTimeSeconds = modifiedTimeSeconds,
        state = BackupState.fromName(state) ?: BackupState.NOT_BACKED_UP,
        retryCount = retryCount,
        lastError = lastError,
        telegramChatId = telegramChatId,
        telegramMessageId = telegramMessageId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        startedAt = startedAt,
        completedAt = completedAt,
        contentHash = contentHash,
    )

    fun List<BackupQueueEntity>.toStats(): BackupQueueStats {
        var queued = 0
        var uploading = 0
        var completed = 0
        var failed = 0
        for (row in this) {
            when (BackupState.fromName(row.state)) {
                BackupState.QUEUED, BackupState.WAITING_FOR_NETWORK, BackupState.WAITING_FOR_AUTH -> queued++
                BackupState.PREPARING, BackupState.UPLOADING -> uploading++
                BackupState.BACKED_UP -> completed++
                BackupState.FAILED -> failed++
                else -> Unit
            }
        }
        return BackupQueueStats(queued = queued, uploading = uploading, completed = completed, failed = failed)
    }

    /**
     * Build the initial queue row for a local item. [id] is the MediaStore id used as the stable
     * dedup key — never the filename. When recognition already computed a content hash it is carried
     * here so the row is not re-hashed and the hash is persisted up front (Phase 7 §32).
     */
    fun LocalMedia.toQueueEntity(
        nowMillis: Long,
        contentHash: String? = null,
        contentSizeBytes: Long? = null,
    ): BackupQueueEntity = BackupQueueEntity(
        localMediaId = id.toString(),
        contentUri = contentUri.toString(),
        mediaType = cloudMediaType().name,
        mimeType = mimeType,
        fileName = displayName,
        sizeBytes = sizeBytes,
        modifiedTimeSeconds = dateMillis / 1000L,
        state = BackupState.QUEUED.name,
        retryCount = 0,
        lastError = null,
        telegramChatId = null,
        telegramMessageId = null,
        telegramFileId = null,
        createdAt = nowMillis,
        updatedAt = nowMillis,
        startedAt = null,
        completedAt = null,
        contentHash = contentHash,
        contentSizeBytes = contentSizeBytes ?: contentHash?.let { sizeBytes },
        hashedAt = if (contentHash != null) nowMillis else null,
    )

    /** A terminal BACKED_UP row associating a local item with a remote identity (Phase 7 §25). */
    fun LocalMedia.toRecognizedQueueEntity(
        remote: com.telepic.domain.backup.RemoteMediaIdentity,
        contentHash: String,
        contentSizeBytes: Long,
        nowMillis: Long,
    ): BackupQueueEntity = BackupQueueEntity(
        localMediaId = id.toString(),
        contentUri = contentUri.toString(),
        mediaType = cloudMediaType().name,
        mimeType = mimeType,
        fileName = displayName,
        sizeBytes = sizeBytes,
        modifiedTimeSeconds = dateMillis / 1000L,
        state = BackupState.BACKED_UP.name,
        retryCount = 0,
        lastError = null,
        telegramChatId = remote.chatId,
        telegramMessageId = remote.messageId,
        telegramFileId = null,
        createdAt = nowMillis,
        updatedAt = nowMillis,
        startedAt = nowMillis,
        completedAt = nowMillis,
        contentHash = contentHash,
        contentSizeBytes = contentSizeBytes,
        hashedAt = nowMillis,
    )

    /** Map the local media category onto the cloud media kind the upload contract expects. */
    fun LocalMedia.cloudMediaType(): CloudMediaType = when (type) {
        MediaType.GIF -> CloudMediaType.GIF
        MediaType.VIDEO -> CloudMediaType.VIDEO
        MediaType.PHOTO -> CloudMediaType.IMAGE
    }
}
