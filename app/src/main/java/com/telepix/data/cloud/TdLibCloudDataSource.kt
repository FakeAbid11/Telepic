package com.telepix.data.cloud

import com.telepix.domain.cloud.ChatCandidate
import com.telepix.domain.cloud.CloudMedia
import com.telepix.domain.cloud.CloudMediaKind
import com.telepix.domain.cloud.CloudPreview
import com.telepix.domain.cloud.CloudMediaType
import com.telepix.domain.cloud.LocalDownloadedMedia
import com.telepix.telegram.TelegramAuthState
import java.io.File
import kotlinx.coroutines.delay
import org.drinkless.tdlib.TdApi

/**
 * Real [CloudDataSource] over the shared Phase 4 TDLib client. Every TDLib call is defensive:
 * unsupported messages are ignored and errors surface as typed exceptions the repository maps to
 * honest UI states (network → offline). Browsing never downloads originals.
 */
class TdLibCloudDataSource(
    private val gateway: com.telepix.telegram.TdLibClientGateway,
    private val authState: kotlinx.coroutines.flow.StateFlow<TelegramAuthState>,
    private val filesDir: File,
) : CloudDataSource {

    override suspend fun searchDestinationCandidates(): List<ChatCandidate> {
        val results = gateway.request(
            TdApi.SearchChatsOnServer(query = ChatValidator.TelepixDestinationTitle, limit = 20),
        )
        if (results is TdApi.Error) throw CloudNetworkException(results.message)
        val foundIds = (results as? TdApi.FoundChats)?.foundChatIds ?: return emptyList()

        return foundIds.mapNotNull { chatId ->
            val chat = gateway.request(TdApi.GetChat(chatId = chatId)) as? TdApi.Chat ?: return@mapNotNull null
            val isChannel = (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true
            val canPost = chat.permissions?.canSendMessages != false
            ChatCandidate(
                chatId = chatId,
                title = chat.title,
                isChannel = isChannel,
                canPostMessages = canPost,
                isAccessible = chat.isAccessible,
            )
        }
    }

    override suspend fun createDestination(): ChatCandidate? {
        val response = gateway.request(
            TdApi.CreateNewSupergroupChat(
                title = ChatValidator.TelepixDestinationTitle,
                description = "Telepix cloud backup destination.",
                location = null,
                isChannel = true,
                isSync = false,
            ),
        )
        if (response is TdApi.Error) throw CloudNetworkException(response.message)
        val chat = (response as? TdApi.CreatedChat)?.let { created ->
            gateway.request(TdApi.GetChat(chatId = created.chatId)) as? TdApi.Chat
        } ?: return null
        return ChatCandidate(
            chatId = chat.id,
            title = chat.title,
            isChannel = true,
            canPostMessages = chat.permissions?.canSendMessages != false,
            isAccessible = chat.isAccessible,
        )
    }

    override suspend fun loadNewestMedia(chatId: Long, limit: Int): List<CloudMedia> {
        val response = gateway.request(
            TdApi.GetChatHistory(
                chatId = chatId,
                fromMessageId = 0,
                offset = 0,
                limit = limit,
                returnLastMessages = true,
            ),
        )
        if (response is TdApi.Error) throw CloudNetworkException(response.message)
        val messages = (response as? TdApi.Messages)?.messages ?: return emptyList()
        return messages.mapNotNull { message -> mapMessage(chatId, message) }
    }

    override suspend fun downloadPreview(media: CloudMedia): CloudPreview? {
        val fileId = media.previewFileId ?: return null
        return downloadFileToPrivateDir(fileId, nameHint = "preview_${media.messageId}")
            ?.let { CloudPreview(localPath = it.absolutePath, width = media.width, height = media.height) }
    }

    override suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia? {
        val fileId = media.originalFileId ?: return null
        val file = downloadFileToPrivateDir(fileId, nameHint = "orig_${media.messageId}") ?: return null
        return LocalDownloadedMedia(localPath = file.absolutePath, chatId = media.chatId, messageId = media.messageId)
    }

    private fun mapMessage(chatId: Long, message: TdApi.Message): CloudMedia? {
        return when (val content = message.content) {
            is TdApi.MessagePhoto -> {
                val photo = content.photo
                CloudMedia(
                    messageId = message.id,
                    chatId = chatId,
                    mediaType = CloudMediaType.IMAGE,
                    mimeType = photo.format ?: "image/jpeg",
                    fileName = photo.fileName,
                    sizeBytes = photo.size.takeIf { it > 0 }?.toLong(),
                    width = photo.width,
                    height = photo.height,
                    durationMs = null,
                    dateEpochSec = message.date,
                    previewFileId = photo.thumbnail?.file?.id,
                    originalFileId = photo.sizes?.maxByOrNull { it.size }?.file?.id,
                )
            }
            is TdApi.MessageVideo -> {
                val video = content.video
                CloudMedia(
                    messageId = message.id,
                    chatId = chatId,
                    mediaType = CloudMediaType.VIDEO,
                    mimeType = video.mimeType,
                    fileName = video.fileName,
                    sizeBytes = video.size.takeIf { it > 0 }?.toLong(),
                    width = video.width,
                    height = video.height,
                    durationMs = video.duration.toLong() * 1000L,
                    dateEpochSec = message.date,
                    previewFileId = video.thumbnail?.file?.id,
                    originalFileId = video.video?.id,
                )
            }
            is TdApi.MessageAnimation -> {
                val animation = content.animation
                CloudMedia(
                    messageId = message.id,
                    chatId = chatId,
                    mediaType = CloudMediaType.GIF,
                    mimeType = animation.mimeType,
                    fileName = animation.fileName,
                    sizeBytes = animation.size.takeIf { it > 0 }?.toLong(),
                    width = animation.width,
                    height = animation.height,
                    durationMs = animation.duration.toLong() * 1000L,
                    dateEpochSec = message.date,
                    previewFileId = animation.thumbnail?.file?.id,
                    originalFileId = animation.animation?.id,
                )
            }
            else -> null
        }
    }

    private suspend fun downloadFileToPrivateDir(fileId: Int, nameHint: String): File? {
        val info = gateway.request(TdApi.GetFile(fileId = fileId))
        if (info is TdApi.Error) return null
        val remote = (info as? TdApi.File)?.remote
        var priority = PRIORITY_PREVIEW
        // Drive the download to completion (bounded poll) instead of assuming a local path.
        repeat(DOWNLOAD_POLLS) {
            val current = gateway.request(TdApi.GetFile(fileId = fileId)) as? TdApi.File
            val local = current?.local
            if (local != null && local.isDownloadCompleted && local.path.isNotEmpty()) {
                return copyToPrivateDir(File(local.path), nameHint)
            }
            gateway.request(TdApi.Download(fileId = fileId, priority = priority, addPagination = false))
            priority = 0
            delay(200)
        }
        return null
    }

    private fun copyToPrivateDir(source: File, nameHint: String): File? = runCatching {
        val target = File(filesDir, nameHint)
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        target
    }.getOrNull()

    private companion object {
        const val PRIORITY_PREVIEW = 1
        const val DOWNLOAD_POLLS = 25
    }
}
