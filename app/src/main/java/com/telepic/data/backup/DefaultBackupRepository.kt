package com.telepic.data.backup

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.telepic.data.backup.db.BackupQueueDao
import com.telepic.data.backup.db.BackupQueueEntity
import com.telepic.data.backup.BackupMapping.toDomain
import com.telepic.data.backup.BackupMapping.toQueueEntity
import com.telepic.data.backup.BackupMapping.toStats
import com.telepic.data.cloud.CloudDestinationInvalidException
import com.telepic.data.cloud.CloudMessageGoneException
import com.telepic.data.cloud.CloudMessagePendingException
import com.telepic.data.cloud.CloudNetworkException
import com.telepic.data.cloud.CloudRepository
import com.telepic.data.cloud.CloudUploadRejectedException
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.backup.BackupItem
import com.telepic.domain.backup.BackupQueueStats
import com.telepic.domain.backup.BackupState
import com.telepic.domain.cloud.CloudMediaType
import com.telepic.domain.cloud.CloudUploadRequest
import com.telepic.domain.media.LocalMedia
import com.telepic.telegram.TelegramAuthState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [BackupRepository] over the Room queue + the [CloudRepository] upload contract. It is the single
 * place the state machine is driven, and it enforces the core invariant: an item only reaches
 * [BackupState.BACKED_UP] after Telegram returns a real remote identity, which [CloudRepository.uploadMedia]
 * persists in the manifest before the queue row is finalized. Concurrency is serialized (a Mutex)
 * to match TDLib's request ordering and to keep destination/manifest writes race-free.
 */
class DefaultBackupRepository(
    private val db: RoomDatabase,
    private val dao: BackupQueueDao,
    private val cloudRepository: CloudRepository,
    private val authState: StateFlow<TelegramAuthState>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val stager: BackupStager? = null,
) : BackupRepository {

    private val processMutex = Mutex()

    override fun observeQueue(): Flow<List<BackupItem>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeStats(): Flow<BackupQueueStats> = dao.observeAll().map { rows -> rows.toStats(maxRetries) }

    override suspend fun enqueue(media: LocalMedia): Boolean = withContext(dispatcher) {
        enqueueInternal(media.toQueueEntity(clock()))
    }

    override suspend fun enqueue(media: LocalMedia, contentHash: String, contentSizeBytes: Long): Boolean =
        withContext(dispatcher) {
            enqueueInternal(media.toQueueEntity(clock(), contentHash, contentSizeBytes))
        }

    /**
     * Inserts [entity], or re-queues the row already holding that local id when its previous attempt
     * ended in a terminal failure.
     *
     * A bare `insertIgnore` treats *any* existing row as "already handled". That is right for a row
     * which is backed up or in flight, but wrong for one that FAILED or was CANCELLED: tapping "Back
     * up" on such a photo was ignored by the unique index and reported as "already backed up" — a
     * false claim about the user's data, with the Backup Center as the only place to retry it.
     * [BackupQueueDao.requeueForRetry] is the same transition that screen's Retry button uses, so the
     * two paths cannot drift apart.
     */
    private suspend fun enqueueInternal(entity: BackupQueueEntity): Boolean = db.withTransaction {
        // insert → find → requeue is atomic. Two concurrent enqueues of the same local id would
        // otherwise both miss the insert and both attempt the re-queue, and a reader landing between
        // the re-queue and the hash write would see a QUEUED row carrying a stale hash.
        if (dao.insertIgnore(entity) != -1L) return@withTransaction true
        val existing = dao.findByLocalId(entity.localMediaId) ?: return@withTransaction false
        val state = BackupState.fromName(existing.state)
        if (state != BackupState.FAILED && state != BackupState.CANCELLED) return@withTransaction false
        val now = clock()
        dao.requeueForRetry(existing.id, now)
        // Keep the content identity current: recognition may have just recomputed the hash because
        // the file changed since the failed attempt, so re-queueing must not resurrect a stale one.
        entity.contentHash?.let {
            dao.persistHash(existing.id, it, entity.contentSizeBytes ?: entity.sizeBytes, now, now)
        }
        true
    }

    override suspend fun enqueueAll(media: List<LocalMedia>): Int = withContext(dispatcher) {
        media.count { enqueue(it) }
    }

    override suspend fun retry(itemId: Long) = withContext(dispatcher) {
        dao.requeueForRetry(itemId, clock())
        Unit
    }

    override suspend fun cancel(itemId: Long) = withContext(dispatcher) {
        dao.cancel(itemId, clock())
        Unit
    }

    override suspend fun recoverInterruptedWork() = withContext(dispatcher) {
        // Mid-flight rows return to QUEUED keeping any pending remote identity, so the next pass
        // confirms an already-accepted send instead of re-uploading it. (BACKED_UP wrote its identity
        // atomically with the state, so a mid-flight row can never already be confirmed.)
        dao.recoverInterruptedToQueued(clock())
        Unit
    }

    override suspend fun processPendingWork(maxItems: Int): BackupProcessSummary =
        withContext(dispatcher) {
            processMutex.withLock {
                var uploaded = 0
                var waiting = 0
                var failed = 0
                var processed = 0
                var waitingForAuth = 0
                var superseded = 0
                var recognized = 0
                // One fetch for the whole pass. The previous shape re-issued this query on every
                // iteration — up to `maxItems` (100 at BackupWorker's batch size) identical SELECTs
                // per run, each re-materialising up to 100 rows — even though the only rows that
                // change are the ones this loop itself writes, and a write can only ever *remove* an
                // item from the actionable set. A single batch is therefore equivalent, and it makes
                // the `attempted` bookkeeping that guarded against re-selecting the same row
                // unnecessary.
                val batch = dao.fetchActionable(maxRetries, maxItems)
                for (item in batch) {
                    processed++
                    when (processOne(item)) {
                        ProcessOutcome.Uploaded -> uploaded++
                        ProcessOutcome.Waiting -> waiting++
                        ProcessOutcome.WaitingForAuth -> waitingForAuth++
                        ProcessOutcome.PermanentFailure -> failed++
                        ProcessOutcome.Superseded -> superseded++
                        ProcessOutcome.RecognizedRemote -> recognized++
                    }
                }
                BackupProcessSummary(
                    uploaded = uploaded,
                    waiting = waiting,
                    failed = failed,
                    processed = processed,
                    waitingForAuth = waitingForAuth,
                    superseded = superseded,
                    recognized = recognized,
                )
            }
        }

    private suspend fun processOne(item: BackupQueueEntity): ProcessOutcome {
        val now = clock()

        if (authState.value !is TelegramAuthState.Authorized) {
            dao.markWaiting(item.id, BackupState.WAITING_FOR_AUTH.name, now)
            return ProcessOutcome.WaitingForAuth
        }

        // A send accepted by a previous run that never confirmed here (timeout / process death):
        // confirm THAT message instead of uploading again — the duplicate-upload guarantee.
        if (item.pendingTelegramChatId != null && item.pendingTelegramMessageId != null) {
            val confirmation: ProcessOutcome? = try {
                val confirmed = cloudRepository.confirmUpload(
                    chatId = item.pendingTelegramChatId,
                    messageId = item.pendingTelegramMessageId,
                    mediaType = mediaTypeOf(item),
                    contentHash = item.contentHash,
                    contentSizeBytes = item.contentSizeBytes ?: item.sizeBytes.takeIf { it > 0 },
                )
                val recorded = dao.markBackedUp(
                    item.id,
                    confirmed.chatId,
                    confirmed.messageId,
                    confirmed.telegramFileId,
                    clock(),
                )
                // Same guard as the send path: a row cancelled while this confirmation was in flight
                // keeps its CANCELLED state and must not be reported as an upload.
                if (recorded > 0) {
                    dao.setPendingWaitAttempts(item.id, null)
                    ProcessOutcome.Uploaded
                } else {
                    dao.setPendingWaitAttempts(item.id, null)
                    TelepicLog.warn(
                        "BackupRepository",
                        "confirmation landed but the queue row was already terminal; not counted as uploaded",
                    )
                    ProcessOutcome.Superseded
                }
            } catch (pending: CloudMessagePendingException) {
                // Still delivering on Telegram's side: the first attempts wait WITHOUT burning the
                // retry budget — a large video may legitimately spend a long time in TDLib's send
                // queue. Past the cap the row takes a bounded transient failure instead, so a send
                // that is stuck (not merely slow) can never loop forever. The pending remote identity
                // is untouched either way, so the item is still confirmed rather than re-sent.
                //
                // The count lives on the row (pendingWaitAttempts), not in this process: a restart
                // resumes the count instead of granting the stuck send another full cap of waits.
                // Access is serialized by [processMutex] (only [processOne] touches it, under the lock).
                val attempts = (item.pendingWaitAttempts ?: 0) + 1
                if (attempts <= MAX_PENDING_WAIT_ATTEMPTS) {
                    dao.setPendingWaitAttempts(item.id, attempts)
                    dao.markWaiting(item.id, BackupState.WAITING_FOR_NETWORK.name, clock())
                } else {
                    dao.setPendingWaitAttempts(item.id, null)
                    dao.recordTransientFailure(
                        item.id,
                        "Upload still pending after $MAX_PENDING_WAIT_ATTEMPTS confirmations.",
                        clock(),
                    )
                }
                ProcessOutcome.Waiting
            } catch (gone: CloudMessageGoneException) {
                // Provably never landed: clear the pending id and fall through to a real upload.
                dao.setPendingWaitAttempts(item.id, null)
                dao.clearPendingRemote(item.id, clock())
                null
            } catch (network: CloudNetworkException) {
                dao.setPendingWaitAttempts(item.id, null)
                dao.recordTransientFailure(item.id, safeError(network), clock())
                ProcessOutcome.Waiting
            } catch (rejected: CloudUploadRejectedException) {
                dao.setPendingWaitAttempts(item.id, null)
                dao.markFailed(item.id, safeError(rejected), clock())
                ProcessOutcome.PermanentFailure
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                dao.setPendingWaitAttempts(item.id, null)
                // An unclassified confirm failure is not provably permanent. This mirrors the upload
                // path's own Throwable handler: without it, one unexpected throwable out of
                // confirmUpload (a Room/SQLite error from manifestDao.upsertAll, say) escaped
                // processOne() and killed the whole worker batch, leaving every mid-flight row
                // unrecovered until the next WorkManager backoff. Recording it as transient instead
                // parks the row in WAITING_FOR_NETWORK for the normal bounded retries.
                dao.recordTransientFailure(item.id, safeError(throwable), clock())
                ProcessOutcome.Waiting
            }
            if (confirmation != null) return confirmation
        }

        val destination = cloudRepository.ensureDestination()
            ?: run {
                // No destination (transiently unavailable, or confirmation still pending). Recording a
                // transient failure bumps the retry budget, so a persistently missing destination parks
                // the row in WAITING_FOR_NETWORK and eventually exhausts into STALLED instead of being
                // re-marked WAITING forever and keeping the worker's `retry()` loop alive indefinitely.
                dao.recordTransientFailure(item.id, "Backup destination is not ready yet.", clock())
                return ProcessOutcome.Waiting
            }

        // The pre-send backstop: the manifest may have gained this content *after* the row was
        // queued — recognition at enqueue time judged against a manifest this install had not yet
        // rebuilt from the channel (a reinstall), or an offline enqueue came back to a channel a
        // later pass already covered. [CloudRepository.findRemoteByContent] rebuilds that manifest
        // from the channel's history before answering when this install does not hold it, so the
        // lookup is against the channel's real contents, and a match is proof the bytes are already
        // remote: the row completes through `confirmRemoteMatch` — the write allowed to carry
        // remote evidence — and not one byte is uploaded twice. The size uses `contentSizeBytes`
        // (the count the hash was computed over, matching the caption marker) with the same
        // provider-size fallback as `confirmUpload`.
        val remote = item.contentHash?.let {
            cloudRepository.findRemoteByContent(it, item.contentSizeBytes ?: item.sizeBytes)
        }
        if (remote != null) {
            val applied = dao.confirmRemoteMatch(item.id, remote.chatId, remote.messageId, clock())
            if (applied == 0) {
                TelepicLog.debug(
                    "BackupRepository",
                    "row ${item.id} already recorded the remote match the manifest just proved",
                )
            }
            return ProcessOutcome.RecognizedRemote
        }

        if (dao.markPreparing(item.id, clock()) == 0) {
            // The row went terminal between the fetch and this write — the user cancelled it, or a
            // concurrent pass finished it. Nothing may be staged or sent for a row the user
            // explicitly cancelled.
            TelepicLog.warn(
                "BackupRepository",
                "row ${item.id} became terminal before staging; nothing was sent",
            )
            return ProcessOutcome.Superseded
        }
        val staged = stager?.stage(item.localMediaId, item.contentUri)
        if (stager != null && staged == null) {
            dao.markFailed(item.id, "Local media is no longer available.", clock())
            return ProcessOutcome.PermanentFailure
        }
        // A stager is not optional in production: without one there is no local file to hand
        // TDLib, and the content URI that stands in for it is not a path TDLib can read. Failing
        // the item honestly beats handing TDLib a `content://` string and reporting an opaque
        // upload rejection.
        val stagedPath = staged?.path ?: run {
            dao.markFailed(item.id, "This device cannot stage the file for upload.", clock())
            return ProcessOutcome.PermanentFailure
        }

        if (dao.markUploading(item.id, clock()) == 0) {
            // The row went terminal between the fetch and this write — the user cancelled it, or a
            // concurrent pass finished it. The upload must NOT go ahead: an explicit cancel that
            // still sends the user's photo to Telegram is the opposite of what was asked for.
            TelepicLog.warn(
                "BackupRepository",
                "row ${item.id} became terminal before the upload started; nothing was sent",
            )
            staged?.cleanup()
            return ProcessOutcome.Superseded
        }
        val request = CloudUploadRequest(
            stagedPath = stagedPath,
            fileName = item.fileName,
            mimeType = item.mimeType,
            mediaType = mediaTypeOf(item),
            sizeBytes = item.sizeBytes.takeIf { it > 0 },
            width = null,
            height = null,
            durationMs = null,
            dateEpochSec = item.modifiedTimeSeconds,
            contentHash = item.contentHash,
            contentSizeBytes = item.contentSizeBytes ?: item.sizeBytes.takeIf { it > 0 },
        )

        // cleanup() must run for EVERY attempt that staged a file — success, transient/permanent
        // failure, OR cancellation. The previous code placed it after the try/catch, so the
        // cooperative CancellationException path (rethrown below) skipped it and leaked the temp file.
        //
        // The one exception is a send Telegram is **still uploading**: the staged copy is the very
        // file TDLib is reading right now, so deleting it aborted the transfer and failed the item
        // permanently. That copy is left in place and swept by the staging directory's startup
        // sweep once the send has resolved.
        var keepStaged = false
        val outcome = try {
            val result = cloudRepository.uploadMedia(
                request = request,
                // The moment TDLib accepts the send, persist the remote identity on the row: every
                // later interruption becomes confirmable instead of re-sendable.
                onSent = { chatId, messageId -> dao.recordPendingRemote(item.id, chatId, messageId, clock()) },
            )
            if (result == null || result.chatId != destination.chatId) {
                // No confirmed identity for the right destination: never mark BACKED_UP.
                dao.recordTransientFailure(item.id, "Upload not confirmed by Telegram.", clock())
                ProcessOutcome.Waiting
            } else {
                // Terminal-state guard in SQL: if the user cancelled while this upload ran, the row
                // stays CANCELLED and this write no-ops. The delivered message then exists without a
                // queue claim — content-addressed recognition dedups it on the next manifest refresh
                // instead of the queue ever overwriting the user's cancel.
                //
                // The affected-row count is checked rather than discarded: reporting a no-oped write
                // as an upload overstated the run's progress, and that count is exactly what the
                // foreground notification shows the user.
                val recorded = dao.markBackedUp(
                    item.id,
                    result.chatId,
                    result.messageId,
                    result.telegramFileId,
                    clock(),
                )
                if (recorded > 0) {
                    ProcessOutcome.Uploaded
                } else {
                    TelepicLog.warn(
                        "BackupRepository",
                        "upload landed but the queue row was already terminal; not counted as uploaded",
                    )
                    ProcessOutcome.Superseded
                }
            }
        } catch (pending: CloudMessagePendingException) {
            // Telegram accepted the send and is still delivering it. The pending remote identity is
            // already on the row, so the next pass *confirms* this message instead of re-sending it.
            // Nothing is failed and no retry budget is spent — a large video legitimately spends
            // minutes in Telegram's queue.
            keepStaged = true
            dao.setPendingWaitAttempts(item.id, null)
            dao.markWaiting(item.id, BackupState.WAITING_FOR_NETWORK.name, clock())
            ProcessOutcome.Waiting
        } catch (network: CloudNetworkException) {
            dao.recordTransientFailure(item.id, safeError(network), clock())
            ProcessOutcome.Waiting
        } catch (invalid: CloudDestinationInvalidException) {
            // The upload was rejected as destination-invalid: surface the real reason and bump the retry
            // budget. Previously this re-marked WAITING_FOR_NETWORK without incrementing retryCount, so an
            // unfixable destination produced an infinite retry loop that kept the worker alive forever.
            dao.recordTransientFailure(item.id, safeError(invalid), clock())
            ProcessOutcome.Waiting
        } catch (rejected: CloudUploadRejectedException) {
            dao.markFailed(item.id, safeError(rejected), clock())
            ProcessOutcome.PermanentFailure
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation // cooperative: recovery returns the row to QUEUED (pending id kept)
        } catch (throwable: Throwable) {
            // An unclassified failure is not provably permanent. Recording it as a *transient* failure
            // bumps the retry budget and parks the row in WAITING_FOR_NETWORK, so it gets the normal
            // bounded retries (and surfaces as STALLED once exhausted) instead of one unexpected
            // throwable burning the whole budget and marking the item permanently FAILED.
            dao.recordTransientFailure(item.id, safeError(throwable), clock())
            ProcessOutcome.Waiting
        } finally {
            if (!keepStaged) staged?.cleanup()
        }
        return outcome
    }

    /** The queue row's cloud media type; a corrupted value must never silently change upload semantics. */
    private fun mediaTypeOf(item: BackupQueueEntity): CloudMediaType =
        runCatching { CloudMediaType.valueOf(item.mediaType) }.getOrElse {
            // Same corruption-hiding class as the CloudMapping/BackupMapping defaults: surface the
            // fallback in logs rather than silently reinterpreting an unknown kind as a photo.
            TelepicLog.warn(
                "BackupRepository",
                "unknown media type '${item.mediaType}' for row ${item.id}; defaulting to IMAGE",
                it,
            )
            CloudMediaType.IMAGE
        }

    /**
     * The user-facing text for a failure.
     *
     * The exception's **type** is part of the message, not just its text: a rejected upload and a
     * network failure both used to read "Upload failed" when the underlying message was empty, and
     * the Backup Center showed the user the same string for two completely different problems. The
     * type is what tells them apart, and it is safe to show — no payload, no path, no credential.
     */
    private fun safeError(throwable: Throwable): String {
        val detail = throwable.message?.takeIf { it.isNotBlank() } ?: "no further detail"
        return "${throwable.javaClass.simpleName}: $detail".take(MAX_ERROR_CHARS)
    }

    private sealed interface ProcessOutcome {
        data object Uploaded : ProcessOutcome
        data object Waiting : ProcessOutcome

        /** Parked because the account is not signed in — not a network condition to retry against. */
        data object WaitingForAuth : ProcessOutcome
        data object PermanentFailure : ProcessOutcome

        /**
         * The remote work succeeded but the queue refused to record it, because the row was already
         * terminal (the user cancelled mid-flight). Neither an upload nor something to retry.
         */
        data object Superseded : ProcessOutcome

        /**
         * The remote manifest already held these exact bytes, so the row completed without sending
         * anything. Neither an upload (nothing left the device) nor something to retry (the content
         * is provably remote).
         */
        data object RecognizedRemote : ProcessOutcome
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 5

        /** How much of a failure message is kept in the queue row's `lastError`. */
        const val MAX_ERROR_CHARS = 160

        /**
         * Consecutive "still sending" confirmations tolerated before the pending send is treated as a
         * bounded transient failure (counted per row in `BackupQueueEntity.pendingWaitAttempts`, so
         * the bound holds across process death). Generous on purpose: the data source's own
         * send-confirmation window is ~60s per attempt, so this is minutes of waiting before the
         * budget starts to decrement.
         *
         * Exactly this many parked waits are granted; the next one burns the retry budget.
         */
        const val MAX_PENDING_WAIT_ATTEMPTS = 20
    }
}
