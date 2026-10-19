package com.telepic.data.cloud

import com.telepic.data.cloud.db.CloudDestinationDao
import com.telepic.data.cloud.db.CloudDestinationEntity
import com.telepic.data.cloud.db.CloudMediaManifestDao
import com.telepic.data.cloud.CloudMapping.toDomain
import com.telepic.data.cloud.CloudMapping.toEntity
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.backup.RemoteMediaIdentity
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

/**
 * Thrown by a [CloudDataSource] for transient network problems (mapped to [CloudStatus.Offline]).
 *
 * [retryAfterSeconds] carries TDLib's own flood-wait figure when a 420 arrived, so a caller can wait
 * the interval Telegram actually asked for instead of a fixed backoff. It duplicates the figure that
 * is already written into [message] on purpose: the queue records the throwable's `message` (never
 * this field) into its `lastError`, so the wait has to be legible from the message alone. Null for
 * every other transient failure.
 */
class CloudNetworkException(
    message: String = "Telegram unavailable",
    val retryAfterSeconds: Int? = null,
) : IOException(message)

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

    /**
     * Lock ordering: **networkMutex is always acquired before dbMutex, never the reverse.** The
     * network lock serializes every TDLib request — [CloudDataSource] calls must stay ordered — so a
     * long `uploadMedia` send-confirmation poll and a [refreshLocked] page walk cannot interleave on
     * the wire, but neither holds up work that only touches Room. The db lock guards exactly that
     * Room work: the manifest read-then-write in [mergePreservingTrusted]'s callers and the
     * destination read-modify-write in [currentDestination]/[persistDestination].
     *
     * The split exists because one lock made a minute-long network poll and a DB-only lookup wait on
     * each other. Nothing here nests dbMutex outside networkMutex; the one nesting is networkMutex
     * outer, dbMutex inner (a page walk or an upload touching the destination/manifest mid-request).
     */
    private val networkMutex = Mutex()
    private val dbMutex = Mutex()

    private val _status = MutableStateFlow<CloudStatus>(CloudStatus.Initializing)
    override val status: StateFlow<CloudStatus> = _status.asStateFlow()

    private val _destinationTitle = MutableStateFlow<String?>(null)
    override val destinationTitle: StateFlow<String?> = _destinationTitle.asStateFlow()

    /**
     * The same-name channel last offered for confirmation. Mirrors the offer in
     * [CloudStatus.DestinationConfirmation] but keeps the full [ChatCandidate], so the confirm path
     * persists exactly what the user saw rather than re-deriving it from a partial status.
     */
    private val _pendingConfirmation = MutableStateFlow<ChatCandidate?>(null)

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
        // Bare, like every other refreshLocked() call site: it now takes networkMutex for its page
        // walk and dbMutex around each page's read+write, so a caller holding either lock across it
        // would deadlock. The walk is still serialized against itself and against every other TDLib
        // request by networkMutex.
        refreshLocked()
    }

    override suspend fun refresh() = withContext(dispatcher) {
        if (!isAuthorized()) {
            _status.value = CloudStatus.NotAuthenticated
            return@withContext
        }
        refreshLocked()
    }

    /**
     * Rebuild the manifest from the channel's history, walking older pages until the walk reaches
     * history this install already holds.
     *
     * Paging here is a **correctness** requirement, not a nicety. The manifest is the reinstall-proof
     * recognition source: `BackupRecognitionRepository` answers "already backed up?" by matching a
     * locally computed SHA-256 against it. A rebuild that could only ever read the newest page left
     * every older row absent, so recognition found nothing, and the entire library older than that
     * page was re-uploaded after a reinstall — while `CloudEntities`/`TdApiCloudMapper`/`README`
     * claimed the rebuilt manifest was as complete as the lost one.
     *
     * The walk is affordable because of the stop rule: a page whose every message id is already
     * recorded means the walk has reached known territory, so everything older is known too. A
     * routine refresh therefore costs a single round trip, while a first refresh after a reinstall
     * (empty manifest) covers the whole channel once. [MAX_REBUILD_PAGES] is a backstop so a
     * pathological history cannot spin forever.
     *
     * Acquires [networkMutex] for the whole walk — every `dataSource` page request has to stay
     * ordered against uploads and destination discovery — and [dbMutex] only around the manifest
     * read and each page's merge write. That is the one legal nesting (network outer, db inner); the
     * `_status` writes stay outside both because it is a plain [MutableStateFlow].
     */
    private suspend fun refreshLocked() {
        networkMutex.withLock {
            val destination = dbMutex.withLock { currentDestination() } ?: run {
                // An outstanding confirmation offer is not a missing destination: overwriting the
                // status here would erase the question the screen is still asking the user.
                if (_pendingConfirmation.value == null) _status.value = CloudStatus.DestinationMissing
                return@withLock
            }
            _status.value = CloudStatus.Refreshing
            try {
                val now = clock()
                // Read once per refresh: this is both the stop signal and the source of the per-row
                // "existing" lookups, replacing one query per discovered row with two per page.
                val known = dbMutex.withLock { manifestDao.messageIds(destination.chatId).toHashSet() }
                var cursor = 0L
                var pages = 0
                while (pages < MAX_REBUILD_PAGES) {
                    val page = dataSource.loadMediaPage(destination.chatId, cursor, PAGE_SIZE)
                    pages++
                    if (page.items.isNotEmpty()) {
                        // Merge the whole page in memory, then write it in one statement — the read
                        // inside mergePreservingTrusted and this write are one dbMutex section.
                        dbMutex.withLock {
                            manifestDao.upsertAll(mergePreservingTrusted(page.items, now))
                        }
                        // Nothing on this page is new, so everything older is already recorded too.
                        if (page.items.none { it.messageId !in known }) break
                    }
                    // Null cursor = the history really ended. An empty page with a cursor still set
                    // means the page held only messages Telepic cannot browse, so keep walking.
                    cursor = page.nextFromMessageId ?: break
                }
                _status.value =
                    if (dbMutex.withLock { manifestDao.count() } == 0) CloudStatus.Empty else CloudStatus.Ready
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
    }

    /**
     * Rebuild the manifest from the channel's history when this install has no rows for
     * [destination] yet — the reinstall case: Room (destination row included until it was just
     * re-discovered) starts empty while the channel does not. Recognition and the upload path's
     * pre-send backstop both answer "already backed up?" from the manifest, so a lookup against a
     * manifest never rebuilt from the channel would treat an established channel as empty and invite
     * a second copy of everything already in it.
     *
     * Only the "no rows for this chat" case walks. A populated manifest is known territory — the
     * walk's own stop rule would end it after a single page anyway. A failed walk (offline)
     * degrades to exactly the old behaviour: [refreshLocked] reports the status and swallows the
     * error, and the caller decides for itself whether the network exists.
     *
     * Takes [dbMutex] only for its own emptiness probe, then releases it before calling
     * [refreshLocked] — that method acquires [networkMutex] itself, and holding dbMutex across it
     * would invert the lock order.
     */
    private suspend fun rebuildManifestIfMissing(destination: TelepicCloudDestination) {
        val empty = dbMutex.withLock { manifestDao.messageIds(destination.chatId).isEmpty() }
        if (empty) refreshLocked()
    }

    override suspend fun findRemoteByContent(contentHash: String, sizeBytes: Long): RemoteMediaIdentity? =
        withContext(dispatcher) {
            // Rebuild-first, because this is the question the upload path asks right before sending,
            // and answering it from a manifest this install has not yet rebuilt (a reinstall) would
            // let a duplicate out the door. The destination read and the lookup are separate dbMutex
            // sections so the walk (which needs networkMutex) never runs while dbMutex is held.
            dbMutex.withLock { currentDestination() }?.let { rebuildManifestIfMissing(it) }
            dbMutex.withLock {
                manifestDao.findByContentHashAndSize(contentHash, sizeBytes)
                    ?.let { RemoteMediaIdentity(it.chatId, it.messageId) }
            }
        }

    override suspend fun ensureDestination(): TelepicCloudDestination? = withContext(dispatcher) {
        if (!isAuthorized()) {
            _status.value = CloudStatus.NotAuthenticated
            return@withContext null
        }
        ensureDestinationLocked()
    }

    /**
     * Acquires [networkMutex] for its whole body — destination discovery is a sequence of ordered
     * TDLib requests — and [dbMutex] only around the destination read and the persistence that
     * implements the check-then-write. That single network section is what keeps two concurrent
     * `ensureDestination` calls from both searching and both creating a channel.
     */
    private suspend fun ensureDestinationLocked(): TelepicCloudDestination? {
        return networkMutex.withLock {
            val existing = dbMutex.withLock { currentDestination() }
            if (existing != null) return@withLock existing

            try {
                val candidates = dataSource.searchDestinationCandidates()
                // An owned, validated channel is used immediately — no consent needed.
                val owned = candidates.firstOrNull { it.isValid() }
                if (owned != null) return@withLock persistDestination(owned)
                // A postable-but-not-owned same-name channel is *offered*, never auto-selected.
                // Nothing is persisted here: that is the whole point of
                // [DestinationVerdict.NeedsConfirmation], and discarding it (as this path used to)
                // demoted a usable channel to DestinationMissing and silently created a second one
                // on top of it.
                val ambiguous = candidates.firstOrNull { it.needsConfirmation() }
                if (ambiguous != null) {
                    val verdict = ChatValidator.validate(ambiguous) as DestinationVerdict.NeedsConfirmation
                    dbMutex.withLock { _pendingConfirmation.value = ambiguous }
                    _status.value = CloudStatus.DestinationConfirmation(
                        chatId = verdict.chatId,
                        title = ambiguous.title,
                        reason = verdict.reason,
                    )
                    return@withLock null
                }
                createOwnDestinationLocked()
            } catch (network: CloudNetworkException) {
                _status.value = CloudStatus.Offline
                null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _status.value = CloudStatus.Failed(safeMessage(throwable))
                null
            }
        }
    }

    /**
     * Confirms the offered same-name channel: the user has just been shown it and said yes, so it is
     * persisted and browsed. Guarded on the offer still being outstanding — a tap that arrives after
     * a decline (or after the offer was already resolved) persists nothing.
     */
    override suspend fun confirmDestination(): TelepicCloudDestination? = withContext(dispatcher) {
        if (!isAuthorized()) {
            _status.value = CloudStatus.NotAuthenticated
            return@withContext null
        }
        if (_status.value !is CloudStatus.DestinationConfirmation) return@withContext null
        val candidate = _pendingConfirmation.value ?: return@withContext null
        // persistDestination takes dbMutex for the destination read-modify-write. The offer is
        // cleared only once it is actually persisted, so a failed write leaves it standing for a
        // retry; [refreshLocked] is called afterwards without either lock held.
        val destination = persistDestination(candidate)
        dbMutex.withLock { _pendingConfirmation.value = null }
        _destinationTitle.value = destination.title
        refreshLocked()
        destination
    }

    /**
     * Declines the offered same-name channel and creates one this account owns instead. The offer is
     * dropped before anything is created, so a later tap cannot resurrect a channel the user refused.
     */
    override suspend fun createNewDestination(): TelepicCloudDestination? = withContext(dispatcher) {
        if (!isAuthorized()) {
            _status.value = CloudStatus.NotAuthenticated
            return@withContext null
        }
        if (_status.value !is CloudStatus.DestinationConfirmation) return@withContext null
        dbMutex.withLock { _pendingConfirmation.value = null }
        _status.value = CloudStatus.Connecting
        // networkMutex for the create request (createOwnDestinationLocked persists inside it under
        // dbMutex), then refreshLocked afterwards on its own — never both held across the refresh.
        val destination = networkMutex.withLock { createOwnDestinationLocked() }
        if (destination != null) {
            _destinationTitle.value = destination.title
            refreshLocked()
        }
        destination
    }

    /**
     * Creates a fresh Telepic-owned destination, or reports [CloudStatus.DestinationMissing]. Callers
     * must already hold [networkMutex]: the create request is TDLib work, and calling it while a
     * caller held [dbMutex] instead would invert the lock order.
     */
    private suspend fun createOwnDestinationLocked(): TelepicCloudDestination? {
        val created = dataSource.createDestination()?.takeIf { it.isValid() }
        if (created == null) {
            _status.value = CloudStatus.DestinationMissing
            return null
        }
        return persistDestination(created)
    }

    override suspend fun getPreview(media: CloudMedia): CloudPreview? = withContext(dispatcher) {
        try {
            // The data source is not internally synchronized, so even this read-only preview request
            // is serialized under networkMutex — a preview must not race a manifest rebuild or an
            // upload's own TDLib traffic. No db state is touched, so dbMutex is not needed.
            networkMutex.withLock { dataSource.downloadPreview(media) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // A provider/transport fault used to be indistinguishable from "this media has no
            // preview", which made a failing preview path invisible in the field. The message id is
            // the whole diagnostic: never the local path, never a filename.
            TelepicLog.warn("Cloud", "preview unavailable for messageId=${media.messageId}", throwable)
            null
        }
    }

    override suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia? =
        withContext(dispatcher) {
            try {
                // networkMutex only for the (potentially long) download request; the manifest flag
                // below is its own dbMutex section so a completed download never holds dbMutex across
                // the network call and never holds networkMutex across the write.
                val result = networkMutex.withLock { dataSource.downloadOriginal(media) }
                if (result != null) {
                    dbMutex.withLock { manifestDao.setDownloaded(media.chatId, media.messageId, true) }
                }
                result
            } catch (cancellation: CancellationException) {
                // Returning null here would read as "the file is unavailable" to the Viewer and
                // mark a perfectly good cloud item undownloadable.
                throw cancellation
            } catch (throwable: Throwable) {
                TelepicLog.warn("Cloud", "original download failed for messageId=${media.messageId}", throwable)
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
        // networkMutex is the outer lock for the whole send: the destination read, the long poll and
        // the manifest write all belong to one ordered TDLib operation, so a concurrent refresh
        // cannot walk pages mid-send (and the destination cannot be repointed under the send).
        networkMutex.withLock {
            val destination = dbMutex.withLock { currentDestination() }
                ?: throw CloudDestinationInvalidException("No Telepic Backup destination")
            val result = dataSource.upload(destination.chatId, request, onProgress, onSent)
            // Remote confirmation arrived: record it under its stable identity, preserving any
            // existing trusted hash (upload rows carry a real hash; discovery rows carry none).
            dbMutex.withLock {
                manifestDao.upsertAll(mergePreservingTrusted(listOf(fromUpload(request, result)), clock()))
            }
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
        // networkMutex outer (the confirm request must stay ordered in TDLib), dbMutex inner for the
        // manifest write — the same nesting refreshLocked and uploadMedia use.
        networkMutex.withLock {
            val confirmed = dataSource.confirmSend(chatId, messageId, mediaType)
            // Record the confirmed remote in the manifest under its identity, carrying the trusted
            // content hash from the queue row so content recognition dedups this upload too — even
            // when its confirmation arrived late, via this path instead of the original send run.
            dbMutex.withLock {
                manifestDao.upsertAll(
                    mergePreservingTrusted(
                        listOf(
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
                                // No provider size is known on this path, so the hashed size stands
                                // in for both — the pair stays internally consistent either way.
                                contentSizeBytes = contentSizeBytes,
                            ),
                        ),
                        clock(),
                    ),
                )
            }
            confirmed
        }
    }

    /**
     * The rows to write for [items]: each remote row wins, except for the fields only this device
     * knows — a trusted content identity, a completed download, resolved file ids, and the original
     * `createdAt`.
     *
     * [CloudMediaManifestEntity.contentSizeBytes] is preserved rather than taken from the discovered
     * row, because the two sizes describe different things and only one of them is checkable here.
     * A refresh legitimately overwrites `sizeBytes` with Telegram's own figure for the stored file
     * (which is *not* the original's size for a recompressed photo), while the hashed size has to
     * survive for the hash lookup to keep matching. The discovered row supplies it only when the
     * caption marker carried it, which is how a rebuilt manifest recovers the identity at all.
     *
     * Takes the whole page at once and resolves every "existing row" in **one** query, so a page of N
     * rows costs one read and one write instead of N of each. The read-then-write is not atomic on
     * its own, so every caller wraps both this read and its own `upsertAll` in one [dbMutex]
     * section — and identity is (chatId, messageId), so a preserved hash always belongs to the same
     * Telegram message and repeated refreshes stay idempotent.
     */
    private suspend fun mergePreservingTrusted(
        items: List<CloudMedia>,
        now: Long,
    ): List<com.telepic.data.cloud.db.CloudMediaManifestEntity> {
        if (items.isEmpty()) return emptyList()
        // Every item in a page comes from one chatId, so a single scoped lookup covers the page.
        val chatId = items.first().chatId
        val existing = manifestDao.findByIds(chatId, items.map { it.messageId })
            .associateBy { it.messageId }
        return items.map { media ->
            val entity = media.toEntity(now)
            val prior = existing[entity.messageId]
            if (prior == null) {
                entity
            } else {
                entity.copy(
                    contentHash = entity.contentHash ?: prior.contentHash,
                    contentSizeBytes = entity.contentSizeBytes ?: prior.contentSizeBytes,
                    isDownloaded = entity.isDownloaded || prior.isDownloaded,
                    previewFileId = entity.previewFileId ?: prior.previewFileId,
                    originalFileId = entity.originalFileId ?: prior.originalFileId,
                    createdAt = prior.createdAt,
                )
            }
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
        // The size the hash was computed over, so recognition can compare like with like even when
        // the provider's `sizeBytes` is absent or stale.
        contentSizeBytes = request.contentSizeBytes ?: request.sizeBytes,
    )

    /** Reads the destination row. Callers must hold [dbMutex]; it is the read half of the RMW. */
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

    /**
     * Writes the destination row, preserving its original `createdAt`. Takes [dbMutex] itself: the
     * `find`-then-`upsert` is a read-modify-write that must not interleave with another writer of
     * the same row.
     */
    private suspend fun persistDestination(candidate: ChatCandidate): TelepicCloudDestination =
        dbMutex.withLock { persistDestinationLocked(candidate) }

    private suspend fun persistDestinationLocked(candidate: ChatCandidate): TelepicCloudDestination {
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

    private fun ChatCandidate.needsConfirmation(): Boolean =
        ChatValidator.validate(this) is DestinationVerdict.NeedsConfirmation

    private fun isAuthorized(): Boolean = authState.value is TelegramAuthState.Authorized

    private fun safeMessage(throwable: Throwable): String =
        (throwable.message ?: "Telegram cloud is unavailable").take(160)

    private companion object {
        const val PROVIDER = "telegram"

        /** Items requested per history page. */
        const val PAGE_SIZE = 50

        /**
         * Backstop on the manifest rebuild walk. In steady state the walk stops after the first page
         * (everything on it is already known), so this only ever applies to a full rebuild — where it
         * caps the one-time cost at 10,000 items rather than letting a pathological history spin.
         */
        const val MAX_REBUILD_PAGES = 200
    }
}
