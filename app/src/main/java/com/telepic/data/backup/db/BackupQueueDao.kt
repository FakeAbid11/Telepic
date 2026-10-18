package com.telepic.data.backup.db

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
 *
 * Terminal states (BACKED_UP / CANCELLED / FAILED) are enforced **in SQL**: every state UPDATE is
 * guarded so a late worker write can never resurrect or overwrite a row the user cancelled or that
 * already completed. UPDATE queries return the affected-row count so callers can tell whether the
 * transition applied.
 */
@Dao
interface BackupQueueDao {

    @Query("SELECT * FROM backup_queue ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<BackupQueueEntity>>

    /**
     * A lightweight projection (id + state + retries) for the Photos backup-status map — avoids
     * pulling full rows (paths, metadata) just to color tiles. Room maps this POJO by column name.
     * `retryCount` is included so retry-exhausted rows can surface as STALLED, not fake "queued".
     */
    @Query("SELECT localMediaId, state, retryCount FROM backup_queue")
    fun observeStatusRows(): Flow<List<BackupStatusRow>>

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
     *
     * Matches [contentSizeBytes], the byte count the hash was computed over, rather than the
     * provider-reported `sizeBytes`: the caller passes the *hashed* size, and the two disagree
     * whenever a provider reports SIZE as 0 or stale — which silently missed the dedup and queued
     * the same bytes twice. `COALESCE` keeps rows hashed before that column existed working.
     */
    @Query(
        "SELECT * FROM backup_queue WHERE contentHash = :contentHash " +
            "AND COALESCE(contentSizeBytes, sizeBytes) = :sizeBytes " +
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

    @Query(
        "UPDATE backup_queue SET state = 'PREPARING', startedAt = COALESCE(startedAt, :now), updatedAt = :now, lastError = NULL " +
            "WHERE id = :id AND state NOT IN ('BACKED_UP', 'CANCELLED', 'FAILED')",
    )
    suspend fun markPreparing(id: Long, now: Long): Int

    @Query(
        "UPDATE backup_queue SET state = 'UPLOADING', startedAt = COALESCE(startedAt, :now), updatedAt = :now " +
            "WHERE id = :id AND state NOT IN ('BACKED_UP', 'CANCELLED', 'FAILED')",
    )
    suspend fun markUploading(id: Long, now: Long): Int

    /** The only path to BACKED_UP: writes the remote identity and the terminal state together. */
    @Query(
        "UPDATE backup_queue SET state = 'BACKED_UP', telegramChatId = :chatId, telegramMessageId = :messageId, " +
            "telegramFileId = :fileId, lastError = NULL, completedAt = :now, updatedAt = :now, " +
            "pendingTelegramChatId = NULL, pendingTelegramMessageId = NULL " +
            "WHERE id = :id AND state NOT IN ('BACKED_UP', 'CANCELLED', 'FAILED')",
    )
    suspend fun markBackedUp(id: Long, chatId: Long, messageId: Long, fileId: Int?, now: Long): Int

    /**
     * The remote manifest already holds these exact bytes, so the content **is** backed up — this is
     * the one write allowed to move a terminal `FAILED`/`CANCELLED` row to `BACKED_UP`, because the
     * proof is remote and not a worker retrying its own guess. [markBackedUp]'s denylist exists to stop
     * a late worker from resurrecting a row; recognition carries evidence a worker can never have, so it
     * uses this instead. Reusing [markBackedUp] here made the write a guaranteed no-op, which left
     * already-backed-up media displayed as `FAILED` and re-hashed on every scan.
     *
     * Also fills the remote identity onto a `BACKED_UP` row that somehow lacks one (the guard's
     * `telegramMessageId IS NULL` half), and is a genuine no-op only when the row is already complete —
     * so repeated scans cost nothing and never rewrite a finished row.
     */
    @Query(
        "UPDATE backup_queue SET state = 'BACKED_UP', telegramChatId = :chatId, telegramMessageId = :messageId, " +
            "lastError = NULL, completedAt = COALESCE(completedAt, :now), updatedAt = :now, " +
            "pendingTelegramChatId = NULL, pendingTelegramMessageId = NULL " +
            "WHERE id = :id AND (state != 'BACKED_UP' OR telegramMessageId IS NULL)",
    )
    suspend fun confirmRemoteMatch(id: Long, chatId: Long, messageId: Long, now: Long): Int

    /**
     * Persist the remote identity the moment TDLib accepts a send (before delivery confirms).
     * Terminal-state guarded: a row cancelled mid-upload keeps its CANCELLED state and the late
     * identity write simply no-ops.
     */
    @Query(
        "UPDATE backup_queue SET pendingTelegramChatId = :chatId, pendingTelegramMessageId = :messageId, updatedAt = :now " +
            "WHERE id = :id AND state NOT IN ('BACKED_UP', 'CANCELLED', 'FAILED')",
    )
    suspend fun recordPendingRemote(id: Long, chatId: Long, messageId: Long, now: Long): Int

    /** The pending send provably never landed (CloudMessageGoneException) — a re-send is safe. */
    @Query(
        "UPDATE backup_queue SET pendingTelegramChatId = NULL, pendingTelegramMessageId = NULL, updatedAt = :now " +
            "WHERE id = :id",
    )
    suspend fun clearPendingRemote(id: Long, now: Long): Int

    /**
     * Persist the parked-wait counter of the pending-send confirmation loop (the wait cap in
     * `DefaultBackupRepository`). Its own statement rather than folded into a state write: every
     * resolve path resets it, and some of those (a successful confirm on an already-terminal row)
     * no-op the guarded state update — the counter still has to go back to NULL.
     *
     * [attempts] is nullable on purpose: NULL is "no parked waits", the same thing a reset restores
     * and what rows migrated from a version without this column read as.
     */
    @Query("UPDATE backup_queue SET pendingWaitAttempts = :attempts WHERE id = :id")
    suspend fun setPendingWaitAttempts(id: Long, attempts: Int?)

    @Query(
        "UPDATE backup_queue SET state = :state, updatedAt = :now " +
            "WHERE id = :id AND state NOT IN ('BACKED_UP', 'CANCELLED', 'FAILED')",
    )
    suspend fun markWaiting(id: Long, state: String, now: Long): Int

    /** Record a transient failure: bump retries, store the error, wait for network. */
    @Query(
        "UPDATE backup_queue SET state = 'WAITING_FOR_NETWORK', retryCount = retryCount + 1, lastError = :error, updatedAt = :now " +
            "WHERE id = :id AND state NOT IN ('BACKED_UP', 'CANCELLED', 'FAILED')",
    )
    suspend fun recordTransientFailure(id: Long, error: String, now: Long): Int

    /** Record a permanent failure (not retried). */
    @Query(
        "UPDATE backup_queue SET state = 'FAILED', lastError = :error, updatedAt = :now " +
            "WHERE id = :id AND state NOT IN ('BACKED_UP', 'CANCELLED', 'FAILED')",
    )
    suspend fun markFailed(id: Long, error: String, now: Long): Int

    /**
     * A user-requested (manual) retry: returns the item to QUEUED and RESETS the retry budget so a
     * previously exhausted item becomes actionable again. Automatic retries stay bounded by
     * [maxRetries] via [recordTransientFailure]; this deliberate reset is the manual path's policy,
     * so an item stuck at `retryCount >= maxRetries` can never be permanently ineligible after the
     * UI reports it was retried. Only BACKED_UP rows can never be re-queued — a confirmed upload is
     * done, and re-queueing it would duplicate the remote message.
     */
    @Query(
        "UPDATE backup_queue SET state = 'QUEUED', retryCount = 0, lastError = NULL, updatedAt = :now " +
            "WHERE id = :id AND state != 'BACKED_UP'",
    )
    suspend fun requeueForRetry(id: Long, now: Long): Int

    /** A user cancel never overwrites a completed or already-terminal row (see class KDoc). */
    @Query(
        "UPDATE backup_queue SET state = 'CANCELLED', updatedAt = :now " +
            "WHERE id = :id AND state NOT IN ('BACKED_UP', 'CANCELLED', 'FAILED')",
    )
    suspend fun cancel(id: Long, now: Long): Int

    /**
     * Crash recovery on worker startup: a row left mid-flight by a previous process returns to
     * QUEUED — **keeping any pending remote identity**. The next processing pass then confirms the
     * already-accepted message instead of re-sending it, so a kill or timeout can never duplicate
     * a post. (Rows that reached BACKED_UP wrote their identity atomically with the state, so no
     * mid-flight row can already be confirmed.)
     */
    @Query(
        "UPDATE backup_queue SET state = 'QUEUED', updatedAt = :now " +
            "WHERE state IN ('PREPARING', 'UPLOADING')",
    )
    suspend fun recoverInterruptedToQueued(now: Long): Int
}
