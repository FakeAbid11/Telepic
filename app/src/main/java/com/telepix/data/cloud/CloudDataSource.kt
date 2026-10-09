package com.telepix.data.cloud

import com.telepix.domain.cloud.ChatCandidate
import com.telepix.domain.cloud.CloudMedia
import com.telepix.domain.cloud.CloudPreview
import com.telepix.domain.cloud.CloudUploadProgress
import com.telepix.domain.cloud.CloudUploadRequest
import com.telepix.domain.cloud.CloudUploadResult
import com.telepix.domain.cloud.LocalDownloadedMedia

/**
 * A TDLib-agnostic seam for the Telegram cloud operations the repository needs.
 *
 * Isolating it lets the repository (discovery → validation → persistence → browsing → upload) be
 * fully unit-tested with a fake, while the real TDLib implementation lives in one defensive class.
 */
interface CloudDataSource {
    /** Candidate chats matching the Telepix destination (mapped to validation-friendly facts). */
    suspend fun searchDestinationCandidates(): List<ChatCandidate>

    /** Create the Telepic Backup channel where permitted; returns the created chat, or null. */
    suspend fun createDestination(): ChatCandidate?

    /** Load the newest [limit] supported media items from [chatId] (newest-first). */
    suspend fun loadNewestMedia(chatId: Long, limit: Int): List<CloudMedia>

    /** Download the smallest practical preview for [media]. Never fetches the original. */
    suspend fun downloadPreview(media: CloudMedia): CloudPreview?

    /** Explicitly download the original for [media] into app-private storage. */
    suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia?

    /**
     * Upload a staged media file to [chatId] and return Telegram's confirmation with the resulting
     * remote identity. Transient failures throw [CloudNetworkException]; unsupported/permanent
     * failures throw [CloudUploadRejectedException]; it must never return a fabricated result.
     */
    suspend fun upload(
        chatId: Long,
        request: CloudUploadRequest,
        onProgress: (CloudUploadProgress) -> Unit,
    ): CloudUploadResult
}
