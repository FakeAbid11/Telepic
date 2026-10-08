package com.telepix.data.backup

import com.telepix.data.backup.db.BackupQueueDao
import com.telepix.data.backup.db.BackupQueueEntity
import com.telepix.data.backup.hash.ContentHasher
import com.telepix.domain.backup.BackupRecognitionResult
import com.telepix.domain.backup.BackupState
import com.telepix.domain.backup.ContentHashResult
import com.telepix.domain.backup.RecognitionUnavailableReason
import com.telepix.domain.backup.RemoteMediaIdentity
import com.telepix.domain.media.LocalMedia
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The content-recognition boundary: decide whether a local item is already backed up **by content**
 * (SHA-256 + size) — never by filename, path, date, album or MediaStore id. It consults the local
 * queue first (cheap, avoids re-hashing), then the remote cloud manifest (the reinstall-proof
 * source), and only reports [BackupRecognitionResult.NeedsBackup] when no trusted remote record
 * matches. A hash/read failure is [BackupRecognitionResult.Unavailable], never "backed up".
 *
 * Knows nothing about TDLib: remote identity comes only from the manifest.
 */
interface BackupRecognitionRepository {
    suspend fun recognize(media: LocalMedia): BackupRecognitionResult

    /** Persist a local→remote association + hash so future scans recognize instantly. */
    suspend fun markRecognized(media: LocalMedia, remote: RemoteMediaIdentity, hash: String, sizeBytes: Long)
}

class DefaultBackupRecognitionRepository(
    private val queueDao: BackupQueueDao,
    private val manifestDao: com.telepix.data.cloud.db.CloudMediaManifestDao,
    private val hasher: ContentHasher,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : BackupRecognitionRepository {

    override suspend fun recognize(media: LocalMedia): BackupRecognitionResult = withContext(dispatcher) {
        val localMediaId = media.id.toString()
        val queueRow = queueDao.findByLocalId(localMediaId)

        // 1) The local queue is the authoritative, cheapest first check.
        when (queueRow?.let { BackupState.fromName(it.state) }) {
            BackupState.BACKED_UP ->
                if (queueRow.telegramChatId != null && queueRow.telegramMessageId != null) {
                    return@withContext BackupRecognitionResult.AlreadyBackedUp(
                        localMediaId,
                        RemoteMediaIdentity(queueRow.telegramChatId, queueRow.telegramMessageId),
                    )
                }
            BackupState.QUEUED, BackupState.PREPARING, BackupState.UPLOADING,
            BackupState.WAITING_FOR_NETWORK, BackupState.WAITING_FOR_AUTH,
            -> return@withContext BackupRecognitionResult.Pending(queueRow.id)
            else -> Unit // FAILED / CANCELLED / none → re-evaluate by content
        }

        // 2) Obtain a content hash: reuse the queue's cached hash when size + modified match,
        //    otherwise stream-compute it (§16/§17). Failures are never "backed up" (§42).
        val cached = queueRow?.takeIf { it.contentHashMatches(media) }?.contentHash
        val hash = when {
            cached != null -> ContentHashResult(cached, queueRow!!.contentSizeBytes ?: media.sizeBytes)
            else -> computeHash(media, queueRow) ?: return@withContext unavailable(media)
        }

        // Persist a freshly computed hash onto the existing row so repeat scans reuse it.
        if (cached == null && queueRow != null) {
            val now = clock()
            queueDao.persistHash(queueRow.id, hash.sha256, hash.sizeBytes, now, now)
        }

        // 3) Remote recognition by content hash + size (§11/§12). Deterministic single match.
        val match = manifestDao.findByContentHashAndSize(hash.sha256, hash.sizeBytes)
        if (match != null) {
            val remote = RemoteMediaIdentity(match.chatId, match.messageId)
            markRecognized(media, remote, hash.sha256, hash.sizeBytes)
            return@withContext BackupRecognitionResult.AlreadyBackedUp(localMediaId, remote)
        }

        // 4) Local cross-identity dedup (§46): identical bytes already queued under another local
        //    id are treated as pending, so the same content is never queued twice for upload.
        queueDao.findActiveByContentHash(hash.sha256, hash.sizeBytes)?.let { active ->
            if (active.localMediaId != localMediaId) return@withContext BackupRecognitionResult.Pending(active.id)
        }

        return@withContext BackupRecognitionResult.NeedsBackup(hash.sha256, hash.sizeBytes)
    }

    private suspend fun computeHash(media: LocalMedia, queueRow: BackupQueueEntity?): ContentHashResult? {
        val expected = hasher.hashSize(media.contentUri)
        return try {
            val result = hasher.hash(media.contentUri)
            // §44: a byte-count that disagrees with the provider's size means the file changed
            // mid-hash — discard rather than persist a torn hash.
            if (expected >= 0 && result.sizeBytes >= 0 && expected != result.sizeBytes) null else result
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun unavailable(media: LocalMedia): BackupRecognitionResult {
        val reason = if (hasher.isReadable(media.contentUri)) {
            RecognitionUnavailableReason.HASH_FAILED
        } else {
            RecognitionUnavailableReason.SOURCE_UNREADABLE
        }
        return BackupRecognitionResult.Unavailable(reason)
    }

    private fun BackupQueueEntity.contentHashMatches(media: LocalMedia): Boolean {
        val hash = contentHash ?: return false
        // Cache key is NOT uri/id alone (§18): require the same size AND modified timestamp.
        return contentSizeBytes == media.sizeBytes && modifiedTimeSeconds == media.dateMillis / 1000L && hash.isNotEmpty()
    }

    override suspend fun markRecognized(media: LocalMedia, remote: RemoteMediaIdentity, hash: String, sizeBytes: Long) =
        withContext(dispatcher) {
            val existing = queueDao.findByLocalId(media.id.toString())
            val now = clock()
            if (existing == null) {
                queueDao.insertIgnore(media.toRecognizedQueueEntity(remote, hash, sizeBytes, now))
            } else {
                queueDao.markBackedUp(existing.id, remote.chatId, remote.messageId, telegramFileId = null, now = now)
                queueDao.persistHash(existing.id, hash, sizeBytes, now, now)
            }
        }
}
