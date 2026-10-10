package com.telepic.data.backup

import com.telepic.domain.backup.BackupItem
import com.telepic.domain.backup.BackupQueueStats
import com.telepic.domain.media.LocalMedia
import kotlinx.coroutines.flow.Flow

/**
 * The backup engine's own repository boundary. The UI (Backup Center) and the Worker talk to this,
 * never to Room/TDLib directly. Keeping queue logic here satisfies "avoid putting queue logic in
 * Compose screens" and lets the whole pipeline be tested against a fake [com.telepic.data.cloud.CloudRepository].
 */
interface BackupRepository {
    fun observeQueue(): Flow<List<BackupItem>>
    fun observeStats(): Flow<BackupQueueStats>

    /** Queue one local item; a no-op (returning false) when it is already queued. */
    suspend fun enqueue(media: LocalMedia): Boolean

    /**
     * Queue one item that recognition already fingerprinted, carrying its content hash so it is
     * persisted up front and the worker does not re-hash it (Phase 7). The default delegates to the
     * hash-less [enqueue] so test fakes need not implement the two-arg form.
     */
    suspend fun enqueue(media: LocalMedia, contentHash: String, contentSizeBytes: Long): Boolean = enqueue(media)

    /** Queue a batch, returning how many were newly added. */
    suspend fun enqueueAll(media: List<LocalMedia>): Int

    suspend fun retry(itemId: Long)

    suspend fun cancel(itemId: Long)

    /** Repair rows a previous process left mid-flight (crash recovery). */
    suspend fun recoverInterruptedWork()

    /**
     * Process up to [maxItems] actionable queue rows through prepare → upload → persist, each
     * strictly following the state machine. Returns the aggregate of what happened.
     */
    suspend fun processPendingWork(maxItems: Int): BackupProcessSummary
}

/** Aggregate of one Worker run over the queue. */
data class BackupProcessSummary(
    val uploaded: Int = 0,
    val waiting: Int = 0,
    val failed: Int = 0,
    val processed: Int = 0,
) {
    val hasRetryableWork: Boolean get() = waiting > 0
}
