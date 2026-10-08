package com.telepix.data.backup.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The persistent backup queue (Phase 6). Survives process death, restart and reboots — it is the
 * source of truth for backup state. [localMediaId] is unique so re-enqueuing the same MediaStore
 * item cannot create a second active row. [telegramChatId] / [telegramMessageId] / [telegramFileId]
 * are written together with the BACKED_UP state only after Telegram confirms the upload; they are
 * the crash-recovery evidence that an upload already succeeded. [contentHash] is reserved for
 * Phase 7.
 */
@Entity(
    tableName = "backup_queue",
    indices = [
        Index(value = ["localMediaId"], unique = true),
        Index(value = ["state"]),
        Index(value = ["updatedAt"]),
        Index(value = ["telegramChatId", "telegramMessageId"]),
    ],
)
data class BackupQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localMediaId: String,
    val contentUri: String,
    val mediaType: String,
    val mimeType: String?,
    val fileName: String?,
    val sizeBytes: Long,
    val modifiedTimeSeconds: Long,
    val state: String,
    val retryCount: Int,
    val lastError: String?,
    val telegramChatId: Long?,
    val telegramMessageId: Long?,
    val telegramFileId: Int?,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val contentHash: String?,
)
