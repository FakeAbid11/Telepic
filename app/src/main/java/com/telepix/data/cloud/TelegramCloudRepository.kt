package com.telepix.data.cloud

import com.telepix.data.cloud.db.CloudDestinationDao
import com.telepix.data.cloud.db.CloudDestinationEntity
import com.telepix.data.cloud.db.CloudMediaManifestDao
import com.telepix.data.cloud.CloudMapping.toDomain
import com.telepix.data.cloud.CloudMapping.toEntity
import com.telepix.domain.cloud.ChatCandidate
import com.telepix.domain.cloud.ChatValidator
import com.telepix.domain.cloud.CloudMedia
import com.telepix.domain.cloud.CloudPreview
import com.telepix.domain.cloud.CloudStatus
import com.telepix.domain.cloud.CloudUploadProgress
import com.telepix.domain.cloud.CloudUploadRequest
import com.telepix.domain.cloud.CloudUploadResult
import com.telepix.domain.cloud.DestinationVerdict
import com.telepix.domain.cloud.LocalDownloadedMedia
import com.telepix.domain.cloud.TelepixCloudDestination
import com.telepix.telegram.TelegramAuthState
import java.io.IOException
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

/** Thrown when the destination exists but is not a valid Telepix destination. */
class CloudDestinationInvalidException(val reason: String) : Exception(reason)

/**
 * Thrown by a [CloudDataSource] when Telegram permanently rejects an upload (unsupported media,
 * rejected file, invalid destination). Unlike [CloudNetworkException] this is not worth retrying.
 */
class CloudUploadRejectedException(message: String) : Exception(message)

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
            manifestDao.upsertAll(items.map { it.toEntity(now) })
            _status.value = if (manifestDao.count() == 0) CloudStatus.Empty else CloudStatus.Ready
        } catch (network: CloudNetworkException) {
            _status.value = CloudStatus.Offline
        } catch (invalid: CloudDestinationInvalidException) {
            _status.value = CloudStatus.DestinationInvalid(invalid.reason)
        } catch (throwable: Throwable) {
            _status.value = CloudStatus.Failed(safeMessage(throwable))
        }
    }

    override suspend fun ensureDestination(): TelepixCloudDestination? = withContext(dispatcher) {
        if (!isAuthorized()) {
            _status.value = CloudStatus.NotAuthenticated
            return@withContext null
        }
        mutex.withLock { ensureDestinationLocked() }
    }

    private suspend fun ensureDestinationLocked(): TelepixCloudDestination? {
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
        } catch (throwable: Throwable) {
            _status.value = CloudStatus.Failed(safeMessage(throwable))
        }
        return null
    }

    override suspend fun getPreview(media: CloudMedia): CloudPreview? = withContext(dispatcher) {
        try {
            dataSource.downloadPreview(media)
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
    ): CloudUploadResult? = withContext(dispatcher) {
        if (!isAuthorized()) {
            throw CloudNetworkException("Not authenticated")
        }
        mutex.withLock {
            val destination = currentDestination() ?: throw CloudDestinationInvalidException("No Telepix Backup destination")
            val result = dataSource.upload(destination.chatId, request, onProgress)
            // Remote confirmation arrived: record it in the manifest under its stable identity.
            manifestDao.upsertAll(listOf(fromUpload(request, result).toEntity(clock())))
            result
        }
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

    private suspend fun currentDestination(): TelepixCloudDestination? {
        val entity = destinationDao.find(PROVIDER) ?: return null
        return TelepixCloudDestination(
            chatId = entity.chatId,
            title = entity.title,
            isChannel = true,
            canPostMessages = true,
            isValidated = entity.validated,
        )
    }

    private suspend fun persistDestination(candidate: ChatCandidate): TelepixCloudDestination {
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
        return TelepixCloudDestination(
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
