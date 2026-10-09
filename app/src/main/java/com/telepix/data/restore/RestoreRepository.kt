package com.telepix.data.restore

import com.telepix.data.backup.hash.ContentStreamSource
import com.telepix.data.backup.hash.hashContent
import com.telepix.data.cloud.CloudRepository
import com.telepix.domain.cloud.CloudMedia
import java.io.File

sealed interface RestoreResult {
    /** The original was downloaded, verified and committed to public media storage. */
    data class Restored(val localUri: String, val contentHash: String?) : RestoreResult
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
 * Completes the PRD restore flow for a single cloud item: explicitly download the original (never
 * automatic), verify its bytes (SHA-256 recheck when the manifest carries a trusted hash), then
 * publish it into public media storage through the Android-supported writer. Success is reported ONLY
 * after publication commits; any earlier step failing leaves an honest error and never a false
 * "restored" state, and abandoned partial work is cleaned by the publisher.
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

        val file = File(downloaded.localPath)
        if (!file.exists() || file.length() <= 0L) {
            return RestoreResult.Failed(RestoreFailure.MISSING_REMOTE)
        }

        val verification = runCatching {
            hashContent(ContentStreamSource { file.inputStream() }, expectedSize = null)
        }.getOrNull() ?: return RestoreResult.Failed(RestoreFailure.VERIFY_FAILED)

        // When the manifest carries a trusted content hash, a mismatch means the bytes are not the
        // original we recorded — refuse rather than publish a corrupt/unexpected file.
        val expected = media.contentHash
        if (expected != null && !expected.equals(verification.sha256, ignoreCase = true)) {
            return RestoreResult.Failed(RestoreFailure.VERIFY_FAILED)
        }

        return when (val published = publisher.publish(downloaded.localPath, media.mimeType, media.fileName)) {
            is PublishResult.Inserted -> RestoreResult.Restored(published.uri, verification.sha256)
            PublishResult.NoSpace -> RestoreResult.Failed(RestoreFailure.LOW_STORAGE)
            PublishResult.Failed -> RestoreResult.Failed(RestoreFailure.PUBLISH_FAILED)
        }
    }
}
