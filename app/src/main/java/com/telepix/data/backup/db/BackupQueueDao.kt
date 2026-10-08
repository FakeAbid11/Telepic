package com.telepix.data.backup.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room access to the backup queue. All state changes go through targeted updates (never a full
 * row rewrite) so the transitions stay atomic and the BACKED_UP update writes the remote identity
 * and the state together — there is no window where a row claims BACKED_UP without a persisted
 * remote id.
 */
@Dao
interface BackupQueueDao {

    @Query("SELECT * FROM backup_queue ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<BackupQueueEntity>>

    /** Insert only when the same local media is not already queued; returns -1 when ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: BackupQueueEntity): Long

    @Query("SELECT * FROM backup_queue WHERE localMediaId = :localMediaId LIMIT 1")
    suspend fun findByLocalId(localMediaId: String): BackupQueueEntity?

    /** Persist a computed content identity (hash + size + when hashed) on a queue row. */
    @Query(
        "UPDATE backup_queue SET contentHash = :hash, contentSizeBytes = :sizeBytes, hashedAt = :hashedAt, updatedAt = :now " +
            "WHERE id = :id",
    )
    suspend fun persistHash(id: Long, hash: String, sizeBytes: Long, hashedAt: Long, now: Long)

    /** The active (non-terminal) queue row for a local item, if one exists. */
    @Query(
        "SELECT * FROM backup_queue WHERE localMediaId = :localMediaId " +
            "AND state IN ('QUEUED', 'PREPARING', 'UPLOADING', 'WAITING_FOR_NETWORK', 'WAITING_FOR_AUTH') LIMIT 1",
    )
    suspend fun findActiveByLocalId(localMediaId: String): BackupQueueEntity?

    /**
     * An active row already carrying the same content hash — used to avoid queueing a second upload
     * of identical bytes reached through a *different* local identity (§46 duplicate prevention).
     */
    @Query(
        "SELECT * FROM backup_queue WHERE contentHash = :contentHash AND sizeBytes = :sizeBytes " +
            "AND state IN ('QUEUED', 'PREPARING', 'UPLOADING', 'WAITING_FOR_NETWORK', 'WAITING_FOR_AUTH') LIMIT 1",
    )
    suspend fun findActiveByContentHash(contentHash: String, sizeBytes: Long): BackupQueueEntity?

    /** The next items a worker may act on, oldest first, respecting the retry limit. */
    @Query(
        "SELECT * FROM backup_queue " +
            "WHERE state IN ('QUEUED', 'WAITING_FOR_NETWORK', 'WAITING_FOR_AUTH') " +
            "AND retryCount < :maxRetries " +
            "ORDER BY createdAt ASC LIMIT :limit",
    )
    suspend fun fetchActionable(maxRetries: Int, limit: Int): List<BackupQueueEntity>

    @Query("SELECT COUNT(*) FROM backup_queue WHERE state = :state")
    fun observeCountByState(state: String): Flow<Int>

    @Query("UPDATE backup_queue SET state = 'PREPARING', startedAt = COALESCE(startedAt, :now), updatedAt = :now, lastError = NULL WHERE id = :id")
    suspend fun markPreparing(id: Long, now: Long)

    @Query("UPDATE backup_queue SET state = 'UPLOADING', startedAt = COALESCE(startedAt, :now), updatedAt = :now WHERE id = :id")
    suspend fun markUploading(id: Long, now: Long)

    /** The only path to BACKED_UP: writes the remote identity and the terminal state together. */
    @Query(
        "UPDATE backup_queue SET state = 'BACKED_UP', telegramChatId = :chatId, telegramMessageId = :messageId, " +
            "telegramFileId = :fileId, lastError = NULL, completedAt = :now, updatedAt = :now WHERE id = :id",
    )
    suspend fun markBackedUp(id: Long, chatId: Long, messageId: Long, fileId: Int?, now: Long)

    @Query("UPDATE backup_queue SET state = :state, updatedAt = :now WHERE id = :id")
    suspend fun markWaiting(id: Long, state: String, now: Long)

    /** Record a transient failure: bump retries, store the error, wait for network. */
    @Query("UPDATE backup_queue SET state = 'WAITING_FOR_NETWORK', retryCount = retryCount + 1, lastError = :error, updatedAt = :now WHERE id = :id")
    suspend fun recordTransientFailure(id: Long, error: String, now: Long)

    /** Record a permanent failure (not retried). */
    @Query("UPDATE backup_queue SET state = 'FAILED', lastError = :error, updatedAt = :now WHERE id = :id")
    suspend fun markFailed(id: Long, error: String, now: Long)

    @Query("UPDATE backup_queue SET state = 'QUEUED', retryCount = retryCount + 1, lastError = NULL, updatedAt = :now WHERE id = :id")
    suspend fun requeueForRetry(id: Long, now: Long)

    @Query("UPDATE backup_queue SET state = 'CANCELLED', updatedAt = :now WHERE id = :id")
    suspend fun cancel(id: Long, now: Long)

    /**
     * Crash recovery on worker startup: a row left mid-flight by a previous process, with no
     * persisted remote id, can only be safely retried — it is returned to QUEUED. Rows that *do*
     * carry a remote id are already effectively uploaded and are finalized as BACKED_UP.
     */
    @Query(
        "UPDATE backup_queue SET state = 'QUEUED', updatedAt = :now " +
            "WHERE state IN ('PREPARING', 'UPLOADING') AND telegramMessageId IS NULL",
    )
    suspend fun recoverInterruptedWithoutIdentity(now: Long): Int

    @Query(
        "UPDATE backup_queue SET state = 'BACKED_UP', completedAt = :now, updatedAt = :now " +
            "WHERE state IN ('PREPARING', 'UPLOADING') AND telegramMessageId IS NOT NULL",
    )
    suspend fun finalizeInterruptedWithIdentity(now: Long): Int
}
