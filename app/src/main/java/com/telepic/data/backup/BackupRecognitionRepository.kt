package com.telepic.data.backup

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.telepic.data.backup.BackupMapping.toRecognizedQueueEntity
import com.telepic.data.backup.db.BackupQueueDao
import com.telepic.data.backup.db.BackupQueueEntity
import com.telepic.data.backup.hash.ContentHasher
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.backup.BackupRecognitionResult
import com.telepic.domain.backup.BackupState
import com.telepic.domain.backup.ContentHashResult
import com.telepic.domain.backup.RecognitionUnavailableReason
import com.telepic.domain.backup.RemoteMediaIdentity
import com.telepic.domain.media.LocalMedia
import kotlinx.coroutines.CancellationException
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
    private val db: RoomDatabase,
    private val queueDao: BackupQueueDao,
    private val manifestDao: com.telepic.data.cloud.db.CloudMediaManifestDao,
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
            cached != null -> ContentHashResult(cached, queueRow?.contentSizeBytes ?: media.sizeBytes)
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
        return try {
            // `hashSize` is a ContentResolver query in its own right and fails for the same reasons
            // `hash` does: a revoked or *limited* media grant, a provider error, or an item deleted
            // between the scan that found it and this call. It used to sit outside this try, so its
            // failure escaped recognition entirely and killed whoever called it — which, from the
            // onboarding backup step, was the app itself.
            val expected = hasher.hashSize(media.contentUri)
            val result = hasher.hash(media.contentUri)
            // §44: a byte-count that disagrees with the provider's size means the file changed
            // mid-hash — discard rather than persist a torn hash.
            if (expected >= 0 && result.sizeBytes >= 0 && expected != result.sizeBytes) {
                // §44: the file changed while being hashed. Discard rather than persist a torn hash —
                // and say so, because this item will now be re-hashed on every scan with no trace of
                // why recognition keeps refusing to trust the cached one.
                TelepicLog.warn("BackupRecognition", "content changed mid-hash, discarding it")
                null
            } else result
        } catch (cancellation: CancellationException) {
            // A cancelled scan is not a hash failure. Swallowing it here used to keep a rotating or
            // closing screen hashing the whole library after its coroutine had been cancelled.
            throw cancellation
        } catch (throwable: Throwable) {
            TelepicLog.warn("BackupRecognition", "hashing unavailable; the item is queued unhashed", throwable)
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
        // Cache key is NOT uri/id alone (§18): require the same size AND the same date. Note what the
        // second half really is: `modifiedTimeSeconds` is written from `LocalMedia.dateMillis`, which
        // `MediaDateResolver` resolves DATE_TAKEN-first — so this is the *display* date, not the file's
        // modification time, despite the field's name. A same-size edit that preserves DATE_TAKEN (a
        // metadata-only rewrite) therefore reuses a stale hash. Keying on the real modified time needs
        // its own column and a migration; until then this is a known, narrow limitation rather than a
        // guarantee the code should claim.
        return contentSizeBytes == media.sizeBytes && modifiedTimeSeconds == media.dateMillis / 1000L && hash.isNotEmpty()
    }

    override suspend fun markRecognized(media: LocalMedia, remote: RemoteMediaIdentity, hash: String, sizeBytes: Long) {
        withContext(dispatcher) {
            // find → insert/confirm → persistHash is one transaction. A concurrent scan reading
            // between the find and the insert would otherwise create a second row for the same media,
            // and a crash between the confirm and the persistHash would leave a row claiming a remote
            // identity without the hash that justifies it.
            db.withTransaction {
                val existing = queueDao.findByLocalId(media.id.toString())
                val now = clock()
                if (existing == null) {
                    queueDao.insertIgnore(media.toRecognizedQueueEntity(remote, hash, sizeBytes, now))
                } else {
                    // `confirmRemoteMatch`, not `markBackedUp`: recognition reaches here only for rows
                    // the queue's own denylist would refuse (FAILED / CANCELLED), so the old call was a
                    // guaranteed no-op — content already in the cloud stayed displayed as FAILED and was
                    // re-hashed on every scan. A 0 here now means "already complete", which is the only
                    // honest outcome, and is logged rather than discarded.
                    val applied = queueDao.confirmRemoteMatch(existing.id, remote.chatId, remote.messageId, now)
                    if (applied == 0) {
                        TelepicLog.debug(
                            "BackupRecognition",
                            "remote match already recorded for localMediaId=${existing.localMediaId}",
                        )
                    }
                    queueDao.persistHash(existing.id, hash, sizeBytes, now, now)
                }
            }
        }
    }
}
