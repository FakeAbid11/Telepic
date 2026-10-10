package com.telepic.data.backup

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
        dao.insertIgnore(media.toQueueEntity(clock())) != -1L
    }

    override suspend fun enqueue(media: LocalMedia, contentHash: String, contentSizeBytes: Long): Boolean =
        withContext(dispatcher) {
            dao.insertIgnore(media.toQueueEntity(clock(), contentHash, contentSizeBytes)) != -1L
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
                // Attempt each actionable item at most once per call. A transiently failing item is
                // parked in WAITING_FOR_NETWORK (still "actionable"), so without this guard the same
                // item would be re-selected in the same run and burn its whole retry budget in one
                // invocation; WorkManager's backoff, not this loop, provides the retry delay.
                val attempted = HashSet<Long>()
                while (processed < maxItems) {
                    val item = dao.fetchActionable(maxRetries, maxItems).firstOrNull { it.id !in attempted }
                        ?: break
                    attempted += item.id
                    processed++
                    when (processOne(item)) {
                        ProcessOutcome.Uploaded -> uploaded++
                        ProcessOutcome.Waiting -> waiting++
                        ProcessOutcome.PermanentFailure -> failed++
                    }
                }
                BackupProcessSummary(uploaded = uploaded, waiting = waiting, failed = failed, processed = processed)
            }
        }

    private suspend fun processOne(item: BackupQueueEntity): ProcessOutcome {
        val now = clock()

        if (authState.value !is TelegramAuthState.Authorized) {
            dao.markWaiting(item.id, BackupState.WAITING_FOR_AUTH.name, now)
            return ProcessOutcome.Waiting
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
                dao.markBackedUp(item.id, confirmed.chatId, confirmed.messageId, confirmed.telegramFileId, clock())
                ProcessOutcome.Uploaded
            } catch (pending: CloudMessagePendingException) {
                // Still delivering on Telegram's side: wait WITHOUT burning the retry budget — a
                // large video may legitimately spend a long time in TDLib's send queue.
                dao.markWaiting(item.id, BackupState.WAITING_FOR_NETWORK.name, clock())
                ProcessOutcome.Waiting
            } catch (gone: CloudMessageGoneException) {
                // Provably never landed: clear the pending id and fall through to a real upload.
                dao.clearPendingRemote(item.id, clock())
                null
            } catch (network: CloudNetworkException) {
                dao.recordTransientFailure(item.id, safeError(network), clock())
                ProcessOutcome.Waiting
            } catch (rejected: CloudUploadRejectedException) {
                dao.markFailed(item.id, safeError(rejected), clock())
                ProcessOutcome.PermanentFailure
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            }
            if (confirmation != null) return confirmation
        }

        val destination = cloudRepository.ensureDestination()
            ?: run {
                dao.markWaiting(item.id, BackupState.WAITING_FOR_NETWORK.name, now)
                return ProcessOutcome.Waiting
            }

        dao.markPreparing(item.id, now)
        val staged = stager?.stage(item.localMediaId, item.contentUri)
        if (stager != null && staged == null) {
            dao.markFailed(item.id, "Local media is no longer available.", clock())
            return ProcessOutcome.PermanentFailure
        }
        val stagedPath = staged?.path ?: item.contentUri

        dao.markUploading(item.id, clock())
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
                dao.markBackedUp(item.id, result.chatId, result.messageId, result.telegramFileId, clock())
                ProcessOutcome.Uploaded
            }
        } catch (network: CloudNetworkException) {
            dao.recordTransientFailure(item.id, safeError(network), clock())
            ProcessOutcome.Waiting
        } catch (invalid: CloudDestinationInvalidException) {
            dao.markWaiting(item.id, BackupState.WAITING_FOR_NETWORK.name, clock())
            ProcessOutcome.Waiting
        } catch (rejected: CloudUploadRejectedException) {
            dao.markFailed(item.id, safeError(rejected), clock())
            ProcessOutcome.PermanentFailure
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation // cooperative: recovery returns the row to QUEUED (pending id kept)
        } catch (throwable: Throwable) {
            dao.markFailed(item.id, safeError(throwable), clock())
            ProcessOutcome.PermanentFailure
        } finally {
            staged?.cleanup()
        }
        return outcome
    }

    /** The queue row's cloud media type; a corrupted value must never silently change upload semantics. */
    private fun mediaTypeOf(item: BackupQueueEntity): CloudMediaType =
        runCatching { CloudMediaType.valueOf(item.mediaType) }.getOrDefault(CloudMediaType.IMAGE)

    private fun safeError(throwable: Throwable): String =
        (throwable.message ?: "Upload failed").take(160)

    private sealed interface ProcessOutcome {
        data object Uploaded : ProcessOutcome
        data object Waiting : ProcessOutcome
        data object PermanentFailure : ProcessOutcome
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 5
    }
}
