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
    /**
     * Rows parked because Telegram answered 420 FLOOD_WAIT and named the interval it wants.
     *
     * Counted apart from [waiting] because the two need different responses: a [waiting] row is
     * retried after the scheduler's fixed exponential backoff, while a flood-wait row must not be
     * retried until Telegram's own interval has elapsed. Folding them together was the original
     * defect — a 300s flood wait was retried at 10s, 20s, 40s… and burned the whole retry budget
     * ([com.telepic.data.backup.DefaultBackupRepository.DEFAULT_MAX_RETRIES]) on a condition that
     * would have cleared on its own.
     */
    val floodWaited: Int = 0,
    /**
     * The longest flood-wait interval any row in this batch asked for, in seconds.
     *
     * Null when [floodWaited] is zero. The worker delays its continuation by this much; the wait is
     * enforced by *not running* rather than by sleeping, so the process can die in between without
     * losing the delay (WorkManager persists `initialDelay`).
     */
    val floodWaitSeconds: Int? = null,
) {
    val hasRetryableWork: Boolean get() = waiting > 0

    /**
     * Whether the batch left work that only the passage of time can unblock.
     *
     * Deliberately not folded into [hasRetryableWork]: `Result.retry()` inherits WorkManager's
     * backoff, which is shorter than Telegram's interval and therefore guaranteed to fail again.
     */
    val hasFloodWait: Boolean get() = floodWaitSeconds != null

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
        floodWaited = floodWaited + other.floodWaited,
        // The *longest* wait wins, not the sum or the last: two rows parked by the same 420 share one
        // account-wide interval, and adding them would delay the retry past what Telegram asked for.
        floodWaitSeconds = maxOf(floodWaitSeconds ?: 0, other.floodWaitSeconds ?: 0).takeIf { it > 0 },
    )
}
