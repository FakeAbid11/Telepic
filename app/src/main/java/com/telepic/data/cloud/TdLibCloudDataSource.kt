package com.telepic.data.cloud

import com.telepic.domain.cloud.ChatCandidate
import com.telepic.domain.cloud.ChatValidator
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudMediaType
import com.telepic.domain.cloud.CloudPreview
import com.telepic.domain.cloud.CloudUploadProgress
import com.telepic.domain.cloud.CloudUploadRequest
import com.telepic.domain.cloud.CloudUploadResult
import com.telepic.domain.cloud.LocalDownloadedMedia
import com.telepic.telegram.TdLibClientGateway
import com.telepic.telegram.TelegramAuthState
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.TdApi

/**
 * TDLib-backed [CloudDataSource] implementing the real Telegram cloud operations against the
 * **resolved** `io.github.tdlib-android:core:0.1.1` (TDLib 1.8.x) Java API. Every request shape is
 * built by [TdApiCloudRequests] and every response mapped by [TdApiCloudMapper], both verified
 * against the generated `TdApi` classes (not another TDLib version).
 *
 * It reuses the app's single [TdLibClientGateway] — never a second client. Requests run through the
 * gateway's `request()` seam; there is no TDLib access from any Composable.
 *
 * Honesty guarantees preserved from earlier phases:
 * - Nothing is fabricated. A missing/failed response yields `null`/empty or an honest exception.
 * - An upload returns a [CloudUploadResult] **only** after Telegram confirms the send (the message's
 *   sending state clears); a rejected/failed send throws, so the queue never becomes BACKED_UP on a
 *   guess.
 * - Downloads use a synchronous TDLib download and the local path is verified to exist and be
 *   non-empty before it is reported.
 *
 * Live validation against a real, authorized session on an ARM device is **device-gated**; until it
 * is performed the request/response behaviour is covered by JVM unit tests with a fake gateway and
 * must not be described as a live integration test.
 */
class TdLibCloudDataSource(
    private val gateway: TdLibClientGateway,
    private val authState: StateFlow<TelegramAuthState>,
    private val destinationTitle: String = ChatValidator.TelepicDestinationTitle,
    private val sendPollDelayMs: Long = DEFAULT_SEND_POLL_DELAY_MS,
    private val maxSendAttempts: Int = DEFAULT_MAX_SEND_ATTEMPTS,
) : CloudDataSource {

    private val isReady: Boolean get() = authState.value is TelegramAuthState.Authorized

    override suspend fun searchDestinationCandidates(): List<ChatCandidate> {
        requireAuth()
        val response = gateway.request(TdApiCloudRequests.searchChannels(destinationTitle, SEARCH_LIMIT))
        if (response is TdApi.Error) throw CloudNetworkException("Chat search failed: ${response.message}")
        val chats = response as? TdApi.Chats ?: return emptyList()
        val candidates = ArrayList<ChatCandidate>(chats.chatIds.size)
        for (chatId in chats.chatIds) {
            candidateFor(chatId)?.let { candidates += it }
        }
        return candidates
    }

    override suspend fun createDestination(): ChatCandidate? {
        requireAuth()
        val response = gateway.request(TdApiCloudRequests.createChannel(destinationTitle, ""))
        if (response is TdApi.Error) throw CloudNetworkException("Create destination failed: ${response.message}")
        val chat = response as? TdApi.Chat ?: return null
        // We just created it, so the account is its creator and may post — confirmed by the API.
        return TdApiCloudMapper.chatToCandidate(chat, canPost = true, isOwnedByAccount = true, accessible = true)
    }

    override suspend fun loadNewestMedia(chatId: Long, limit: Int): List<CloudMedia> {
        requireAuth()
        val collected = ArrayList<CloudMedia>(limit)
        var fromMessageId = 0L
        var pages = 0
        while (collected.size < limit && pages < MAX_PAGES) {
            pages++
            val response = gateway.request(TdApiCloudRequests.history(chatId, fromMessageId, 0, PAGE_SIZE))
            if (response is TdApi.Error) throw CloudNetworkException("History failed: ${response.message}")
            val messages = (response as? TdApi.Messages)?.messages ?: break
            if (messages.isEmpty()) break
            for (message in messages) {
                TdApiCloudMapper.messageToCloudMedia(chatId, message)?.let { collected += it }
            }
            fromMessageId = messages.last().id
            if (messages.size < PAGE_SIZE) break
        }
        return collected.take(limit)
    }

    override suspend fun downloadPreview(media: CloudMedia): CloudPreview? {
        requireAuth()
        val path = downloadAndVerify(media.previewFileId, PREVIEW_PRIORITY) ?: return null
        return CloudPreview(localPath = path, width = media.width, height = media.height)
    }

    override suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia? {
        requireAuth()
        val path = downloadAndVerify(media.originalFileId, ORIGINAL_PRIORITY) ?: return null
        return LocalDownloadedMedia(localPath = path, chatId = media.chatId, messageId = media.messageId)
    }

    override suspend fun upload(
        chatId: Long,
        request: CloudUploadRequest,
        onProgress: (CloudUploadProgress) -> Unit,
        onSent: suspend (chatId: Long, messageId: Long) -> Unit,
    ): CloudUploadResult {
        requireAuth()
        val content = TdApiCloudMapper.uploadContent(request)
        val response = gateway.request(TdApiCloudRequests.sendMessage(chatId, content))
        if (response is TdApi.Error) throw classifySendError(response)
        val sent = response as? TdApi.Message ?: throw CloudNetworkException("Unexpected sendMessage response")
        // TDLib accepted the send: hand the real identity to the caller *now*, before polling.
        // If this run later times out or dies, the persisted id lets the next pass confirm the
        // in-flight message instead of re-sending and duplicating the post.
        onSent(chatId, sent.id)
        val confirmed = awaitSendConfirmation(chatId, sent)
        // Telegram confirmed the send; report the real remote identity. Progress is not fabricated:
        // the byte count is only known once, at confirmation, so a single honest sample is emitted.
        request.sizeBytes?.let { onProgress(CloudUploadProgress(uploadedBytes = it, totalBytes = it)) }
        return CloudUploadResult(
            chatId = confirmed.chatId.takeIf { it != 0L } ?: chatId,
            messageId = confirmed.id,
            telegramFileId = TdApiCloudMapper.uploadedFileId(confirmed),
            mediaType = request.mediaType,
        )
    }

    override suspend fun confirmSend(chatId: Long, messageId: Long, mediaType: CloudMediaType): CloudUploadResult {
        requireAuth()
        val response = gateway.request(TdApiCloudRequests.getMessage(chatId, messageId))
        if (response is TdApi.Error) {
            // "message id invalid" (400) is the one TDLib code that proves the send never landed —
            // anything else (network, temporary) stays retryable without clearing the pending id.
            if (response.code == 400) throw CloudMessageGoneException("Telegram has no message $messageId: ${response.message}")
            throw CloudNetworkException("Confirm failed: ${response.message}")
        }
        val message = response as? TdApi.Message ?: throw CloudNetworkException("Unexpected getMessage response")
        when (val state = message.sendingState) {
            null -> return CloudUploadResult(
                chatId = message.chatId.takeIf { it != 0L } ?: chatId,
                messageId = message.id,
                telegramFileId = TdApiCloudMapper.uploadedFileId(message),
                mediaType = mediaType,
            )
            is TdApi.MessageSendingStateFailed ->
                throw CloudUploadRejectedException("Telegram rejected the upload: ${state.error?.message ?: "unknown"}")
            else -> throw CloudMessagePendingException()
        }
    }

    // --- internals ---------------------------------------------------------------------------

    private suspend fun candidateFor(chatId: Long): ChatCandidate? {
        val chatResponse = gateway.request(TdApiCloudRequests.getChat(chatId))
        if (chatResponse is TdApi.Error) return null // inaccessible/deleted → not a candidate
        val chat = chatResponse as? TdApi.Chat ?: return null
        val member = currentUserId()?.let { userId ->
            gateway.request(TdApiCloudRequests.getChatMember(chatId, userId)) as? TdApi.ChatMember
        }
        // Ownership and posting rights come only from the account's real member status, never the
        // title: an admin of someone else's same-name channel is postable but NOT owned.
        return TdApiCloudMapper.chatToCandidate(
            chat,
            canPost = TdApiCloudMapper.canPostFromMember(member),
            isOwnedByAccount = TdApiCloudMapper.isOwnerFromMember(member),
            accessible = true,
        )
    }

    private suspend fun currentUserId(): Long? {
        val me = gateway.request(TdApiCloudRequests.getMe())
        return (me as? TdApi.User)?.id
    }

    /** Synchronously downloads [fileId] and returns a verified, non-empty local path (or null). */
    private suspend fun downloadAndVerify(fileId: Int?, priority: Int): String? {
        if (fileId == null) return null
        val response = gateway.request(TdApiCloudRequests.downloadSynchronously(fileId, priority))
        if (response is TdApi.Error) return null
        val file = response as? TdApi.File ?: return null
        val local = file.local ?: return null
        if (!local.isDownloadingCompleted) return null
        val path = local.path ?: return null
        val onDisk = File(path)
        return if (onDisk.exists() && onDisk.length() > 0L) path else null
    }

    /**
     * Polls the just-sent message until Telegram clears its sending state (success) or reports a
     * failure. Polling (rather than racing the shared update stream) keeps confirmation reliable; a
     * timeout is treated as a transient failure so the queue can retry rather than claim success.
     */
    private suspend fun awaitSendConfirmation(chatId: Long, initial: TdApi.Message): TdApi.Message {
        var current = initial
        var attempts = 0
        while (current.sendingState != null) {
            val state = current.sendingState
            if (state is TdApi.MessageSendingStateFailed) {
                throw CloudUploadRejectedException("Telegram rejected the upload: ${state.error?.message ?: "unknown"}")
            }
            if (attempts++ >= maxSendAttempts) throw CloudNetworkException("Upload confirmation timed out")
            delay(sendPollDelayMs)
            val response = gateway.request(TdApiCloudRequests.getMessage(chatId, current.id))
            if (response is TdApi.Error) throw CloudNetworkException("Confirm failed: ${response.message}")
            current = response as? TdApi.Message ?: throw CloudNetworkException("Unexpected getMessage response")
        }
        return current
    }

    private fun classifySendError(error: TdApi.Error): Exception =
        // TDLib's flood wait is 420 (with "retry after N seconds"); 429/5xx are other transient
        // conditions. All of them re-run through the queue's backoff — only genuine rejections
        // (400s etc.) are permanent failures. Matches telegram/TelegramError.kt's auth-side mapping.
        if (error.code == 420 || error.code == 429 || error.code >= 500) {
            CloudNetworkException("Telegram send failed (${error.code}): ${error.message}")
        } else {
            CloudUploadRejectedException("Telegram rejected the upload (${error.code}): ${error.message}")
        }

    private fun requireAuth() {
        if (!isReady) throw CloudNetworkException("Not authenticated")
    }

    private companion object {
        const val SEARCH_LIMIT = 20
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 20
        const val PREVIEW_PRIORITY = 1
        const val ORIGINAL_PRIORITY = 32
        const val DEFAULT_SEND_POLL_DELAY_MS = 300L
        const val DEFAULT_MAX_SEND_ATTEMPTS = 200 // ~60s at 300ms
    }
}
