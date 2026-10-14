package com.telepic.data.cloud

import com.telepic.domain.cloud.ChatCandidate
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudMediaType
import com.telepic.domain.cloud.CloudPreview
import com.telepic.domain.cloud.CloudUploadProgress
import com.telepic.domain.cloud.CloudUploadRequest
import com.telepic.domain.cloud.CloudUploadResult
import com.telepic.domain.cloud.LocalDownloadedMedia

/**
 * One page of channel history, with the cursor needed to continue where this page stopped.
 *
 * [nextFromMessageId] is `null` **exactly** when the history is exhausted, so a caller can tell
 * "there is nothing older" apart from "this page happened to hold no supported media". The manifest
 * rebuild needs that distinction: a page whose messages Telepic cannot browse still has to be walked
 * past, or a channel holding a few unsupported messages would end the rebuild early.
 */
data class CloudMediaPage(
    /** Supported media found in this page, newest-first. May be empty for an all-unsupported page. */
    val items: List<CloudMedia>,
    /** The `fromMessageId` to pass for the next (older) page, or null when the history ended. */
    val nextFromMessageId: Long?,
)

/**
 * A TDLib-agnostic seam for the Telegram cloud operations the repository needs.
 *
 * Isolating it lets the repository (discovery → validation → persistence → browsing → upload) be
 * fully unit-tested with a fake, while the real TDLib implementation lives in one defensive class.
 */
interface CloudDataSource {
    /** Candidate chats matching the Telepic destination (mapped to validation-friendly facts). */
    suspend fun searchDestinationCandidates(): List<ChatCandidate>

    /** Create the Telepic Backup channel where permitted; returns the created chat, or null. */
    suspend fun createDestination(): ChatCandidate?

    /**
     * Load one page of supported media from [chatId], newest-first, starting at [fromMessageId]
     * (`0` = the newest message). Returns at most [limit] items plus the cursor for the next page.
     *
     * Paging is part of the contract, not an optimisation: the cloud manifest is the reinstall-proof
     * recognition source, so a rebuild that can only ever see the newest page silently declares
     * everything older "not backed up" and re-uploads it. See `TelegramCloudRepository.refreshLocked`.
     */
    suspend fun loadMediaPage(chatId: Long, fromMessageId: Long, limit: Int): CloudMediaPage

    /** Download the smallest practical preview for [media]. Never fetches the original. */
    suspend fun downloadPreview(media: CloudMedia): CloudPreview?

    /** Explicitly download the original for [media] into app-private storage. */
    suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia?

    /**
     * Upload a staged media file to [chatId] and return Telegram's confirmation with the resulting
     * remote identity. Transient failures throw [CloudNetworkException]; unsupported/permanent
     * failures throw [CloudUploadRejectedException]; it must never return a fabricated result.
     *
     * [onSent] fires the moment TDLib **accepts the send** (before delivery is confirmed), with the
     * real (chatId, messageId). Callers persist it immediately so a later timeout can *confirm the
     * existing message* via [confirmSend] instead of re-sending and duplicating the post.
     */
    suspend fun upload(
        chatId: Long,
        request: CloudUploadRequest,
        onProgress: (CloudUploadProgress) -> Unit,
        onSent: suspend (chatId: Long, messageId: Long) -> Unit = { _, _ -> },
    ): CloudUploadResult

    /**
     * Re-check an already-sent message: returns the confirmed identity when Telegram finished
     * delivering, throws [CloudMessagePendingException] while still sending,
     * [CloudMessageGoneException] when the message no longer exists (safe to re-send), or the usual
     * transient/rejected exceptions. Never fabricates confirmation.
     */
    suspend fun confirmSend(chatId: Long, messageId: Long, mediaType: CloudMediaType): CloudUploadResult
}
