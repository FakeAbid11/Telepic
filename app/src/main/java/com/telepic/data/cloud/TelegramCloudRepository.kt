package com.telepic.data.cloud

import com.telepic.data.cloud.db.CloudDestinationDao
import com.telepic.data.cloud.db.CloudDestinationEntity
import com.telepic.data.cloud.db.CloudMediaManifestDao
import com.telepic.data.cloud.CloudMapping.toDomain
import com.telepic.data.cloud.CloudMapping.toEntity
import com.telepic.domain.cloud.ChatCandidate
import com.telepic.domain.cloud.ChatValidator
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudMediaType
import com.telepic.domain.cloud.CloudPreview
import com.telepic.domain.cloud.CloudStatus
import com.telepic.domain.cloud.CloudUploadProgress
import com.telepic.domain.cloud.CloudUploadRequest
import com.telepic.domain.cloud.CloudUploadResult
import com.telepic.domain.cloud.DestinationVerdict
import com.telepic.domain.cloud.LocalDownloadedMedia
import com.telepic.domain.cloud.TelepicCloudDestination
import com.telepic.telegram.TelegramAuthState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Thrown by a [CloudDataSource] for transient network problems (mapped to [CloudStatus.Offline]). */
class CloudNetworkException(message: String = "Telegram unavailable") : IOException(message)

/** Thrown when the destination exists but is not a valid Telepic destination. */
class CloudDestinationInvalidException(val reason: String) : Exception(reason)

/**
 * Thrown by a [CloudDataSource] when Telegram permanently rejects an upload (unsupported media,
 * rejected file, invalid destination). Unlike [CloudNetworkException] this is not worth retrying.
 */
class CloudUploadRejectedException(message: String) : Exception(message)

/**
 * A send TDLib accepted but has not finished delivering yet. Distinct from a failure: the message
 * exists and is still uploading, so the caller must wait — never burn the retry budget or re-send.
 */
class CloudMessagePendingException(message: String = "Telegram is still sending the message") : Exception(message)

/**
 * A previously-sent message that no longer exists on Telegram (e.g. the process was killed before
 * TDLib flushed the send). Only after this may a queue row be safely re-uploaded.
 */
class CloudMessageGoneException(message: String = "The earlier send never reached Telegram") : Exception(message)

/**
 * [CloudRepository] over an injectable [CloudDataSource] + Room persistence + the Phase 4
 * authorization state. Orchestrates discovery → validation → persistence → browsing; performs no
 * uploads and never blocks the caller's thread.
 */
class TelegramCloudRepository(
    private val dataSource: CloudDataSource,
    private val destinationDao: CloudDestinationDao,
    private val manifestDao: CloudMediaManifestDao,
    private val authState: StateFlow<TelegramAuthState>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : CloudRepository {

    private val mutex = Mutex()

    private val _status = MutableStateFlow<CloudStatus>(CloudStatus.Initializing)
    override val status: StateFlow<CloudStatus> = _status.asStateFlow()

    private val _destinationTitle = MutableStateFlow<String?>(null)
    override val destinationTitle: StateFlow<String?> = _destinationTitle.asStateFlow()

    override val media: Flow<List<CloudMedia>> =
        manifestDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun prepare() = withContext(dispatcher) {
        if (!isAuthorized()) {
            _status.value = CloudStatus.NotAuthenticated
            return@withContext
        }
        _status.value = CloudStatus.Connecting
        val destination = ensureDestination() ?: return@withContext
        _destinationTitle.value = destination.title
        refreshLocked()
    }

    override suspend fun refresh() = withContext(dispatcher) {
        if (!isAuthorized()) {
            _status.value = CloudStatus.NotAuthenticated
            return@withContext
        }
        mutex.withLock { refreshLocked() }
    }

    private suspend fun refreshLocked() {
        val destination = currentDestination() ?: run {
            _status.value = CloudStatus.DestinationMissing
            return
        }
        _status.value = CloudStatus.Refreshing
        try {
            val items = dataSource.loadNewestMedia(destination.chatId, PAGE_SIZE)
            val now = clock()
            items.forEach { upsertPreservingTrusted(it.toEntity(now)) }
            _status.value = if (manifestDao.count() == 0) CloudStatus.Empty else CloudStatus.Ready
        } catch (network: CloudNetworkException) {
            _status.value = CloudStatus.Offline
        } catch (invalid: CloudDestinationInvalidException) {
            _status.value = CloudStatus.DestinationInvalid(invalid.reason)
        } catch (cancellation: CancellationException) {
            // A cancelled refresh is not a failure: publishing Failed here would overwrite the
            // status a newer run just set, and the scope is already tearing down.
            throw cancellation
        } catch (throwable: Throwable) {
            _status.value = CloudStatus.Failed(safeMessage(throwable))
        }
    }

    override suspend fun ensureDestination(): TelepicCloudDestination? = withContext(dispatcher) {
        if (!isAuthorized()) {
            _status.value = CloudStatus.NotAuthenticated
            return@withContext null
        }
        mutex.withLock { ensureDestinationLocked() }
    }

    private suspend fun ensureDestinationLocked(): TelepicCloudDestination? {
        currentDestination()?.let { return it }

        try {
            val found = dataSource.searchDestinationCandidates()
                .firstOrNull { it.isValid() }
                ?: dataSource.createDestination()?.takeIf { it.isValid() }

            if (found == null) {
                _status.value = CloudStatus.DestinationMissing
                return null
            }
            return persistDestination(found)
        } catch (network: CloudNetworkException) {
            _status.value = CloudStatus.Offline
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            _status.value = CloudStatus.Failed(safeMessage(throwable))
        }
        return null
    }

    override suspend fun getPreview(media: CloudMedia): CloudPreview? = withContext(dispatcher) {
        try {
            dataSource.downloadPreview(media)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            null
        }
    }

    override suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia? =
        withContext(dispatcher) {
            try {
                val result = dataSource.downloadOriginal(media)
                if (result != null) {
                    manifestDao.setDownloaded(media.chatId, media.messageId, true)
                }
                result
            } catch (cancellation: CancellationException) {
                // Returning null here would read as "the file is unavailable" to the Viewer and
                // mark a perfectly good cloud item undownloadable.
                throw cancellation
            } catch (throwable: Throwable) {
                null
            }
        }

    /**
     * Upload a staged file to the validated destination. Guards on authorization + a valid
     * destination, delegates to the data source, and — only after Telegram confirms success with a
     * remote identity — records the item in the cloud manifest. Transient ([CloudNetworkException])
     * and permanent ([CloudUploadRejectedException]) errors propagate so the engine can decide
     * retry vs fail; nothing here fabricates success.
     */
    override suspend fun uploadMedia(
        request: CloudUploadRequest,
        onProgress: (CloudUploadProgress) -> Unit,
        onSent: suspend (chatId: Long, messageId: Long) -> Unit,
    ): CloudUploadResult? = withContext(dispatcher) {
        if (!isAuthorized()) {
            throw CloudNetworkException("Not authenticated")
        }
        mutex.withLock {
            val destination = currentDestination() ?: throw CloudDestinationInvalidException("No Telepic Backup destination")
            val result = dataSource.upload(destination.chatId, request, onProgress, onSent)
            // Remote confirmation arrived: record it under its stable identity, preserving any
            // existing trusted hash (upload rows carry a real hash; discovery rows carry none).
            upsertPreservingTrusted(fromUpload(request, result).toEntity(clock()))
            result
        }
    }

    override suspend fun confirmUpload(
        chatId: Long,
        messageId: Long,
        mediaType: CloudMediaType,
        contentHash: String?,
        contentSizeBytes: Long?,
    ): CloudUploadResult = withContext(dispatcher) {
        if (!isAuthorized()) {
            throw CloudNetworkException("Not authenticated")
        }
        mutex.withLock {
            val confirmed = dataSource.confirmSend(chatId, messageId, mediaType)
            // Record the confirmed remote in the manifest under its identity, carrying the trusted
            // content hash from the queue row so content recognition dedups this upload too — even
            // when its confirmation arrived late, via this path instead of the original send run.
            upsertPreservingTrusted(
                CloudMedia(
                    messageId = confirmed.messageId,
                    chatId = confirmed.chatId,
                    mediaType = confirmed.mediaType,
                    mimeType = null,
                    fileName = null,
                    sizeBytes = contentSizeBytes,
                    width = null,
                    height = null,
                    durationMs = null,
                    dateEpochSec = clock() / 1000L,
                    previewFileId = null,
                    originalFileId = confirmed.telegramFileId,
                    isDownloaded = false,
                    contentHash = contentHash,
                ).toEntity(clock()),
            )
            confirmed
        }
    }

    /**
     * Upserts a manifest row while preserving app-trusted local metadata a remote discovery cannot
     * reconstruct: an existing non-null `contentHash`, a prior `isDownloaded`, preview/original ids
     * and the original `createdAt` are kept unless the incoming row supplies a real value. Identity
     * is (chatId, messageId), so a preserved hash always belongs to the same Telegram message, and
     * repeated refreshes are idempotent.
     */
    private suspend fun upsertPreservingTrusted(entity: com.telepic.data.cloud.db.CloudMediaManifestEntity) {
        val existing = manifestDao.get(entity.chatId, entity.messageId)
        val merged = if (existing == null) {
            entity
        } else {
            entity.copy(
                contentHash = entity.contentHash ?: existing.contentHash,
                isDownloaded = entity.isDownloaded || existing.isDownloaded,
                previewFileId = entity.previewFileId ?: existing.previewFileId,
                originalFileId = entity.originalFileId ?: existing.originalFileId,
                createdAt = existing.createdAt,
            )
        }
        manifestDao.upsertAll(listOf(merged))
    }

    private fun fromUpload(request: CloudUploadRequest, result: CloudUploadResult): CloudMedia = CloudMedia(
        messageId = result.messageId,
        chatId = result.chatId,
        mediaType = result.mediaType,
        mimeType = request.mimeType,
        fileName = request.fileName,
        sizeBytes = request.sizeBytes,
        width = request.width,
        height = request.height,
        durationMs = request.durationMs,
        dateEpochSec = request.dateEpochSec,
        previewFileId = null,
        originalFileId = result.telegramFileId,
        isDownloaded = true,
        contentHash = request.contentHash,
    )

    private suspend fun currentDestination(): TelepicCloudDestination? {
        val entity = destinationDao.find(PROVIDER) ?: return null
        return TelepicCloudDestination(
            chatId = entity.chatId,
            title = entity.title,
            isChannel = true,
            canPostMessages = true,
            isValidated = entity.validated,
        )
    }

    private suspend fun persistDestination(candidate: ChatCandidate): TelepicCloudDestination {
        val now = clock()
        val existing = destinationDao.find(PROVIDER)
        destinationDao.upsert(
            CloudDestinationEntity(
                provider = PROVIDER,
                chatId = candidate.chatId,
                title = candidate.title,
                validated = true,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        _destinationTitle.value = candidate.title
        return TelepicCloudDestination(
            chatId = candidate.chatId,
            title = candidate.title,
            isChannel = candidate.isChannel,
            canPostMessages = candidate.canPostMessages,
            isValidated = true,
        )
    }

    private fun ChatCandidate.isValid(): Boolean =
        ChatValidator.validate(this) is DestinationVerdict.Valid

    private fun isAuthorized(): Boolean = authState.value is TelegramAuthState.Authorized

    private fun safeMessage(throwable: Throwable): String =
        (throwable.message ?: "Telegram cloud is unavailable").take(160)

    private companion object {
        const val PROVIDER = "telegram"
        const val PAGE_SIZE = 50
    }
}
