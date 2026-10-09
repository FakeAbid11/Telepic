package com.telepix.data.restore

import com.telepix.data.backup.hash.ContentStreamSource
import com.telepix.data.backup.hash.hashContent
import com.telepix.data.cloud.CloudRepository
import com.telepix.domain.cloud.CloudMedia
import java.io.File

sealed interface RestoreResult {
    /** The original was downloaded and committed to public media storage. */
    data class Restored(val localUri: String, val restoredContentHash: String?) : RestoreResult
    data class Failed(val reason: RestoreFailure) : RestoreResult
}

enum class RestoreFailure {
    DOWNLOAD_UNAVAILABLE,
    MISSING_REMOTE,
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
 */
interface RestoreRepository {
    suspend fun restore(media: CloudMedia): RestoreResult
}

class DefaultRestoreRepository(
    private val cloudRepository: CloudRepository,
    private val publisher: MediaStorePublisher,
) : RestoreRepository {

    override suspend fun restore(media: CloudMedia): RestoreResult {
        val downloaded = runCatching { cloudRepository.downloadOriginal(media) }.getOrNull()
            ?: return RestoreResult.Failed(RestoreFailure.DOWNLOAD_UNAVAILABLE)

        // A completed download must be present and non-empty; an absent/zero-length file is treated
        // as missing (and a partial/interrupted download never reaches a completed-path here).
        val file = File(downloaded.localPath)
        if (!file.exists() || file.length() <= 0L) {
            return RestoreResult.Failed(RestoreFailure.MISSING_REMOTE)
        }

        // Compute the restored bytes' identity for the record. Unreadable bytes are a real integrity
        // failure (VERIFY_FAILED); this is NOT a comparison against the lossy-representation original.
        val restoredHash = runCatching {
            hashContent(ContentStreamSource { file.inputStream() }, expectedSize = null).sha256
        }.getOrNull() ?: return RestoreResult.Failed(RestoreFailure.VERIFY_FAILED)

        return when (val published = publisher.publish(downloaded.localPath, media.mimeType, media.fileName)) {
            is PublishResult.Inserted -> RestoreResult.Restored(published.uri, restoredHash)
            PublishResult.NoSpace -> RestoreResult.Failed(RestoreFailure.LOW_STORAGE)
            PublishResult.Failed -> RestoreResult.Failed(RestoreFailure.PUBLISH_FAILED)
        }
    }
}
