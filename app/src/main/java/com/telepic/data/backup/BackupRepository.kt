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
     * A different Telegram account is now active. Remote identity from the previous account is not
     * evidence of backup for the new one, so those rows return to QUEUED (cancelled rows stay
     * cancelled). Default no-op for fakes; the real repository serializes against an in-flight pass.
     */
    suspend fun resetForAccountSwitch() = Unit

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
    /** Items parked only because no Telegram session exists — a condition this worker cannot fix. */
    val waitingForAuth: Int = 0,
    /**
     * Uploads that reached Telegram but were **not** recorded on the queue row, because the row was
     * already terminal — a user cancel that arrived while the upload was in flight. Counted apart
     * from [uploaded] so the run's reported progress never claims work the queue refused to record;
     * it deliberately counts toward neither [hasRetryableWork] nor [blockedOnAuth], because there is
     * nothing left to retry and nothing parked on a missing session.
     */
    val superseded: Int = 0,
    /**
     * Rows that completed **without sending anything**, because the remote manifest already held
     * exactly these bytes (the pre-send backstop in `DefaultBackupRepository.processOne` — the
     * proof is the same remote evidence recognition records). Counted apart from [uploaded] so a
     * run never claims bytes it did not upload, and apart from the failure counts because there
     * is nothing left to retry; like [superseded] it deliberately counts toward neither
     * [hasRetryableWork] nor [blockedOnAuth].
     */
    val recognized: Int = 0,
) {
    val hasRetryableWork: Boolean get() = waiting > 0

    /**
     * The batch's only outcome was "there is no session to upload to". Nothing here improves by running
     * again: the rows are parked in [com.telepic.domain.backup.BackupState.WAITING_FOR_AUTH], which
     * deliberately burns no retry budget, so a worker that asked to retry would do so forever — every
     * backoff period, each time with a foreground-service notification. The session's return is what
     * re-triggers the queue instead (see `TelepicApplication`), plus discovery and the Backup Center.
     */
    val blockedOnAuth: Boolean get() = waitingForAuth > 0 && uploaded == 0 && waiting == 0

    /**
     * Combine two batches.
     *
     * A worker run drains the queue in several batches, and the run's own outcome has to be decided
     * on the **total**: a run that uploaded 40 items and then hit an auth-parked row is not
     * "blocked on auth", and a run that uploaded nothing but left a waiting row is.
     */
    operator fun plus(other: BackupProcessSummary): BackupProcessSummary = BackupProcessSummary(
        uploaded = uploaded + other.uploaded,
        waiting = waiting + other.waiting,
        failed = failed + other.failed,
        processed = processed + other.processed,
        waitingForAuth = waitingForAuth + other.waitingForAuth,
        superseded = superseded + other.superseded,
        recognized = recognized + other.recognized,
    )
}
