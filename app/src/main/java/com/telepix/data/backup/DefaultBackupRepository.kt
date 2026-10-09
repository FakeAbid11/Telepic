package com.telepix.data.backup

import com.telepix.data.backup.db.BackupQueueDao
import com.telepix.data.backup.db.BackupQueueEntity
import com.telepix.data.backup.BackupMapping.toDomain
import com.telepix.data.backup.BackupMapping.toQueueEntity
import com.telepix.data.backup.BackupMapping.toStats
import com.telepix.data.cloud.CloudDestinationInvalidException
import com.telepix.data.cloud.CloudNetworkException
import com.telepix.data.cloud.CloudRepository
import com.telepix.data.cloud.CloudUploadRejectedException
import com.telepix.domain.backup.BackupItem
import com.telepix.domain.backup.BackupQueueStats
import com.telepix.domain.backup.BackupState
import com.telepix.domain.cloud.CloudMediaType
import com.telepix.domain.cloud.CloudUploadRequest
import com.telepix.domain.media.LocalMedia
import com.telepix.telegram.TelegramAuthState
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

    override fun observeStats(): Flow<BackupQueueStats> = dao.observeAll().map { it.toStats() }

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
    }

    override suspend fun cancel(itemId: Long) = withContext(dispatcher) {
        dao.cancel(itemId, clock())
    }

    override suspend fun recoverInterruptedWork() = withContext(dispatcher) {
        val now = clock()
        // A row with a persisted remote id already succeeded (the identity write is atomic with
        // BACKED_UP, so this is defensive); anything without one is safely returned to QUEUED.
        dao.finalizeInterruptedWithIdentity(now)
        dao.recoverInterruptedWithoutIdentity(now)
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
            mediaType = runCatching { CloudMediaType.valueOf(item.mediaType) }.getOrDefault(CloudMediaType.IMAGE),
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
            val result = cloudRepository.uploadMedia(request)
            if (result == null || result.chatId != destination.chatId) {
                // No confirmed identity for the right destination: never mark BACKED_UP.
                dao.recordTransientFailure(item.id, "Upload not confirmed by Telegram.", clock())
                ProcessOutcome.Waiting
            } else {
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
            throw cancellation // cooperative: recovery returns the row to QUEUED
        } catch (throwable: Throwable) {
            dao.markFailed(item.id, safeError(throwable), clock())
            ProcessOutcome.PermanentFailure
        } finally {
            staged?.cleanup()
        }
        return outcome
    }

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
