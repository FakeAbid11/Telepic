package com.telepic.data.backup.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The persistent backup queue (Phase 6). Survives process death, restart and reboots — it is the
 * source of truth for backup state. [localMediaId] is unique so re-enqueuing the same MediaStore
 * item cannot create a second active row. [telegramChatId] / [telegramMessageId] / [telegramFileId]
 * are written together with the BACKED_UP state only after Telegram confirms the upload; they are
 * the crash-recovery evidence that an upload already succeeded.
 *
 * Phase 7 fills in the previously reserved [contentHash] (lowercase hex SHA-256) as the content
 * identity, with [contentSizeBytes] as a cheap consistency check and [hashedAt] recording when the
 * hash was computed — the cache-validation hint that lets recognition reuse an unchanged file's
 * hash instead of re-hashing it.
 */
@Entity(
    tableName = "backup_queue",
    indices = [
        Index(value = ["localMediaId"], unique = true),
        Index(value = ["state"]),
        Index(value = ["updatedAt"]),
        Index(value = ["telegramChatId", "telegramMessageId"]),
        Index(value = ["contentHash"]),
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
    val contentSizeBytes: Long? = null,
    val hashedAt: Long? = null,
)

/**
 * A lightweight id+state projection of a queue row. Used to feed the Photos backup-status map
 * without loading full rows (paths, metadata) onto the UI — one batched query, not one per tile.
 */
data class BackupStatusRow(
    val localMediaId: String,
    val state: String,
)
