package com.telepic.domain.backup

/**
 * A single backup queue entry as seen by the UI/engine (the domain view of the Room row). Remote
 * identity ([telegramChatId] / [telegramMessageId]) is populated only after Telegram confirms an
 * upload, and mirrors the invariant that [state] may be [BackupState.BACKED_UP] only when both are
 * present. [contentHash] is reserved for Phase 7 and is never computed here.
 */
data class BackupItem(
    val id: Long,
    val localMediaId: String,
    val contentUri: String,
    val mediaType: String,
    val mimeType: String?,
    val fileName: String?,
    val sizeBytes: Long,
    val modifiedTimeSeconds: Long,
    val state: BackupState,
    val retryCount: Int,
    val lastError: String?,
    val telegramChatId: Long?,
    val telegramMessageId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val contentHash: String? = null,
)

/** Aggregate queue counts the Backup Center reports. */
data class BackupQueueStats(
    val queued: Int = 0,
    val uploading: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
) {
    val activeCount: Int get() = queued + uploading
    val totalProcessed: Int get() = completed + failed
}
