package com.telepic.data.restore

import com.telepic.data.backup.hash.ContentStreamSource
import com.telepic.data.backup.hash.hashContent
import com.telepic.data.cloud.CloudRepository
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.cloud.CloudMedia
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface RestoreResult {
    /** The original was downloaded and committed to public media storage. */
    data class Restored(val localUri: String, val restoredContentHash: String?) : RestoreResult
    data class Failed(val reason: RestoreFailure) : RestoreResult
}

enum class RestoreFailure {
    DOWNLOAD_UNAVAILABLE,

    /** The download completed but the file is absent or zero-length (presence/size check failed). */
    MISSING_REMOTE,

    /**
     * The downloaded bytes could not be read/hashed. This is an integrity failure of the *download*,
     * NOT a failed comparison against a trusted digest — no such comparison is made for Telegram's
     * (possibly recompressed) stored representation.
     */
    VERIFY_FAILED,

    LOW_STORAGE,
    PUBLISH_FAILED,
}

/**
 * Completes the PRD restore flow for a single cloud item: download the original on an explicit
 * request, confirm transport integrity, and publish into public media storage through the
 * Android-supported writer — reporting success ONLY once publication commits.
 *
 * Integrity contract (deliberately explicit, §6): the manifest's `contentHash` is the identity of the
 * ORIGINAL local file, recorded for recognition/dedup. Telegram stores images as its own photo
 * representation, which can recompress bytes, so the DOWNLOADED bytes are NOT expected to hash equal
 * to that original — comparing them and failing restore would reject legitimate restores. Instead we
 * assert what is actually verifiable without a device: the download produced a present, non-empty,
 * completed file (TDLib's synchronous download yields a completed file or nothing), and we compute
 * the RESTORED bytes' SHA-256 as `restoredContentHash` — the identity of the file we publish, for
 * downstream dedup. No claim of byte-for-byte equality with the source is made for transformed media.
 *
 * Duplicate handling is keyed on the true remote identity ([RestoreIdentity]), never on display
 * name/size/MIME: a repeat restore of the same cloud item reuses the copy the [RestoreLedger]
 * recorded for it (after the provider confirms that row still exists), and a *different* item is
 * always published even if it happens to share a name, size and MIME with an earlier one.
 */
interface RestoreRepository {
    suspend fun restore(media: CloudMedia): RestoreResult
}

class DefaultRestoreRepository(
    private val cloudRepository: CloudRepository,
    private val publisher: MediaStorePublisher,
    private val ledger: RestoreLedger? = null,
    // Seam for the restored-bytes digest so the integrity-failure path is testable without a real
    // unreadable file. Production streams the file's bytes; no trusted digest is compared (see the
    // interface KDoc) — the hash is recorded as the published copy's identity.
    private val computeRestoredHash: suspend (File) -> String = { file ->
        hashContent(ContentStreamSource { file.inputStream() }, expectedSize = null).sha256
    },
) : RestoreRepository {

    // Serialises restores of the SAME remote item so two concurrent "Save to device" requests cannot
    // both pass the ledger check and publish a second copy. Distinct items still restore in parallel.
    // Entries are bounded by the number of distinct items restored in the process lifetime.
    private val itemLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun restore(media: CloudMedia): RestoreResult = withContext(Dispatchers.IO) {
        val identity = RestoreIdentity(media.chatId, media.messageId)
        itemLocks.computeIfAbsent(identity.token()) { Mutex() }.withLock {
            restoreLocked(media, identity)
        }
    }

    /**
     * The whole pipeline — download, on-disk existence/length checks, a full streaming SHA-256 of the
     * file, and the MediaStore publish — is file I/O and hashing, so it runs on [Dispatchers.IO]
     * rather than whatever dispatcher the caller happened to be on (the Viewer's `viewModelScope`,
     * i.e. Main), where a large original blocked the UI thread for the length of a hash.
     */
    private suspend fun restoreLocked(media: CloudMedia, identity: RestoreIdentity): RestoreResult {
        // Reuse a previously published copy of THIS remote item, but only after the provider confirms
        // the recorded row still exists — a copy the user deleted must re-publish rather than be
        // reported as a save that is no longer there. Never matched on name/size/MIME.
        ledger?.publishedUri(identity)?.let { recorded ->
            if (publisher.exists(recorded)) {
                return RestoreResult.Restored(recorded, restoredContentHash = null)
            }
            try {
                ledger.forget(identity)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // Dropping a stale mapping is bookkeeping; it must not abort the re-publish.
                TelepicLog.warn("RestoreLedger", "could not forget a stale published uri", throwable)
            }
        }

        // `onFailure { throw }` keeps cancellation flowing: without it a cancelled scope would be
        // reported to the user as "download unavailable".
        val downloaded = runCatching { cloudRepository.downloadOriginal(media) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
            ?: return RestoreResult.Failed(RestoreFailure.DOWNLOAD_UNAVAILABLE)

        // A completed download must be present and non-empty; an absent/zero-length file is treated
        // as missing (and a partial/interrupted download never reaches a completed-path here).
        val file = File(downloaded.localPath)
        if (!file.exists() || file.length() <= 0L) {
            return RestoreResult.Failed(RestoreFailure.MISSING_REMOTE)
        }

        // Compute the restored bytes' identity for the record. Unreadable bytes are a real integrity
        // failure (VERIFY_FAILED); this is NOT a comparison against the lossy-representation original.
        val restoredHash = runCatching { computeRestoredHash(file) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return RestoreResult.Failed(RestoreFailure.VERIFY_FAILED)

        // The capture time travels with the item so the published row is filed under the day the
        // media was *taken*, not the day it was restored. Without it a 2019 photo restored today
        // silently corrupted the library's chronology.
        val dateTakenMillis = media.dateEpochSec?.takeIf { it > 0L }?.times(1000L)

        return when (
            val published = publisher.publish(
                sourcePath = downloaded.localPath,
                mimeType = media.mimeType,
                displayName = media.fileName,
                dateTakenMillis = dateTakenMillis,
            )
        ) {
            is PublishResult.Inserted -> {
                try {
                    ledger?.record(identity, published.uri)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    // The file is already in the gallery. A failed ledger write only costs duplicate
                    // suppression on a later restore; reporting the save as failed would be a lie and
                    // a retry would publish the file twice.
                    TelepicLog.warn("RestoreLedger", "the published uri could not be recorded", throwable)
                }
                RestoreResult.Restored(published.uri, restoredHash)
            }
            PublishResult.NoSpace -> RestoreResult.Failed(RestoreFailure.LOW_STORAGE)
            PublishResult.Failed -> RestoreResult.Failed(RestoreFailure.PUBLISH_FAILED)
        }
    }
}
