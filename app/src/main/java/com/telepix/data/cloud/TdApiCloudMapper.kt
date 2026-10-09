package com.telepix.data.cloud

import com.telepix.domain.cloud.ChatCandidate
import com.telepix.domain.cloud.CloudMedia
import com.telepix.domain.cloud.CloudMediaKind
import com.telepix.domain.cloud.CloudMediaType
import com.telepix.domain.cloud.CloudUploadRequest
import org.drinkless.tdlib.TdApi

/**
 * Pure mapping between the **resolved** TDLib Java API (`org.drinkless.tdlib.TdApi`, TDLib 1.8.x as
 * bundled by `io.github.tdlib-android:core:0.1.1`) and Telepix's TDLib-agnostic cloud domain models.
 *
 * The request/response shapes here were verified against the actual artifact (constructor signatures
 * read from the generated `TdApi` classes) rather than guessed from another TDLib version. This file
 * holds no I/O and constructs no `Client`, so it is unit-testable on the JVM: the `TdApi.*` types are
 * plain Java value objects whose construction does not load the native `tdjni` library.
 *
 * Honesty: mapping is only as good as the live responses; end-to-end behaviour against a real,
 * authorized Telegram session is device-gated and is reported as NOT PERFORMED until validated.
 */
object TdApiCloudMapper {

    /**
     * Reduces a discovered [TdApi.Chat] plus the account's real membership into a [ChatCandidate].
     * [canPost] and [isOwnedByAccount] both derive from the account's genuine chat-member status
     * (creator / admin-with-post-right), never the title. A channel the account merely administers
     * is postable but not owned.
     */
    fun chatToCandidate(
        chat: TdApi.Chat,
        canPost: Boolean,
        isOwnedByAccount: Boolean,
        accessible: Boolean,
    ): ChatCandidate {
        val isChannel = (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true
        return ChatCandidate(
            chatId = chat.id,
            title = chat.title,
            isChannel = isChannel,
            canPostMessages = canPost,
            isAccessible = accessible,
            isOwnedByAccount = isOwnedByAccount,
        )
    }

    /** Whether the account CREATED/owns the chat, from its member status (Creator only). */
    fun isOwnerFromMember(member: TdApi.ChatMember?): Boolean =
        member?.status is TdApi.ChatMemberStatusCreator

    /**
     * Whether the account may post to the destination, from its real chat-member status: the creator
     * always can; an administrator can when granted `canPostMessages`; a plain member of a channel
     * cannot. Anything else (left/banned/restricted/unknown) is treated as "cannot post" — never
     * assumed.
     */
    fun canPostFromMember(member: TdApi.ChatMember?): Boolean = when (val status = member?.status) {
        is TdApi.ChatMemberStatusCreator -> true
        is TdApi.ChatMemberStatusAdministrator -> status.rights?.canPostMessages == true
        else -> false
    }

    /**
     * Maps one remote [TdApi.Message] to a [CloudMedia], or `null` when it is not supported media
     * (text, stickers, contacts, a failed send, etc.) so the scanner can safely skip it. Remote
     * identity is always `(chatId, messageId)` — never the filename.
     */
    fun messageToCloudMedia(chatId: Long, message: TdApi.Message?): CloudMedia? {
        if (message == null) return null
        // A message still failing to send is not a confirmed remote item.
        if (message.sendingState is TdApi.MessageSendingStateFailed) return null
        val dateEpochSec = message.date.toLong()
        return when (val content = message.content) {
            is TdApi.MessagePhoto -> photo(chatId, message.id, content, dateEpochSec)
            is TdApi.MessageVideo -> video(chatId, message.id, content, dateEpochSec)
            is TdApi.MessageAnimation -> animation(chatId, message.id, content, dateEpochSec)
            is TdApi.MessageDocument -> document(chatId, message.id, content, dateEpochSec)
            else -> null
        }
    }

    private fun photo(chatId: Long, messageId: Long, content: TdApi.MessagePhoto, date: Long): CloudMedia? {
        val sizes = content.photo?.sizes ?: return null
        val smallest = sizes.smallestByArea() ?: return null
        val largest = sizes.largestByArea() ?: smallest
        return CloudMedia(
            messageId = messageId,
            chatId = chatId,
            mediaType = CloudMediaType.IMAGE,
            mimeType = "image/jpeg",
            fileName = null,
            sizeBytes = largest.photo?.size,
            width = largest.width.takeIf { it > 0 },
            height = largest.height.takeIf { it > 0 },
            durationMs = null,
            dateEpochSec = date,
            previewFileId = smallest.photo?.id,
            originalFileId = largest.photo?.id,
        )
    }

    private fun video(chatId: Long, messageId: Long, content: TdApi.MessageVideo, date: Long): CloudMedia? {
        val v = content.video ?: return null
        return CloudMedia(
            messageId = messageId,
            chatId = chatId,
            mediaType = CloudMediaType.VIDEO,
            mimeType = v.mimeType,
            fileName = v.fileName,
            sizeBytes = v.video?.size,
            width = v.width.takeIf { it > 0 },
            height = v.height.takeIf { it > 0 },
            durationMs = v.duration.toLong().takeIf { it > 0 }?.times(1000L),
            dateEpochSec = date,
            previewFileId = v.thumbnail?.file?.id,
            originalFileId = v.video?.id,
        )
    }

    private fun animation(chatId: Long, messageId: Long, content: TdApi.MessageAnimation, date: Long): CloudMedia? {
        val a = content.animation ?: return null
        return CloudMedia(
            messageId = messageId,
            chatId = chatId,
            mediaType = CloudMediaType.GIF,
            mimeType = a.mimeType,
            fileName = a.fileName,
            sizeBytes = a.animation?.size,
            width = a.width.takeIf { it > 0 },
            height = a.height.takeIf { it > 0 },
            durationMs = a.duration.toLong().takeIf { it > 0 }?.times(1000L),
            dateEpochSec = date,
            previewFileId = a.thumbnail?.file?.id,
            originalFileId = a.animation?.id,
        )
    }

    private fun document(chatId: Long, messageId: Long, content: TdApi.MessageDocument, date: Long): CloudMedia? {
        val d = content.document ?: return null
        // Documents are only browsable when they are really image/video/gif media sent as a file.
        val type = CloudMediaKind.classify(d.mimeType) ?: return null
        return CloudMedia(
            messageId = messageId,
            chatId = chatId,
            mediaType = type,
            mimeType = d.mimeType,
            fileName = d.fileName,
            sizeBytes = d.document?.size,
            width = null,
            height = null,
            durationMs = null,
            dateEpochSec = date,
            previewFileId = d.thumbnail?.file?.id,
            originalFileId = d.document?.id,
        )
    }

    /**
     * Builds the [TdApi.InputMessageContent] for an upload, using the media-appropriate representation
     * for each supported type over an [TdApi.InputFileLocal] pointing at the staged file (the original
     * bytes are handed to TDLib untouched — Telepix never re-encodes them itself).
     *
     * Note on preservation: Telegram applies its own media pipeline to photos (it may recompress),
     * so byte-for-byte preservation of an *image* is not claimed. Videos/animations are sent as their
     * native media types. This is documented honestly rather than asserted.
     */
    fun uploadContent(request: CloudUploadRequest): TdApi.InputMessageContent {
        val file = TdApi.InputFileLocal(request.stagedPath)
        val caption = TdApi.FormattedText("", null)
        val width = request.width ?: 0
        val height = request.height ?: 0
        val durationSec = (request.durationMs ?: 0L).let { if (it > 0) (it / 1000L).toInt() else 0 }
        return when (request.mediaType) {
            CloudMediaType.IMAGE -> TdApi.InputMessagePhoto(
                TdApi.InputPhoto(file, null, null, intArrayOf(), width, height),
                caption,
                false,
                null,
                false,
            )
            CloudMediaType.VIDEO -> TdApi.InputMessageVideo(
                TdApi.InputVideo(file, null, null, 0, intArrayOf(), durationSec, width, height, true),
                caption,
                false,
                null,
                false,
            )
            CloudMediaType.GIF -> TdApi.InputMessageAnimation(
                TdApi.InputAnimation(file, null, intArrayOf(), durationSec, width, height),
                caption,
                false,
                false,
            )
        }
    }

    /** The confirmed remote identity/file id from a successfully sent message's content. */
    fun uploadedFileId(message: TdApi.Message): Int? = when (val content = message.content) {
        is TdApi.MessagePhoto -> content.photo?.sizes?.largestByArea()?.photo?.id
        is TdApi.MessageVideo -> content.video?.video?.id
        is TdApi.MessageAnimation -> content.animation?.animation?.id
        is TdApi.MessageDocument -> content.document?.document?.id
        else -> null
    }

    private fun Array<TdApi.PhotoSize>.smallestByArea(): TdApi.PhotoSize? =
        filter { it.photo != null }.minByOrNull { areaOf(it) }

    private fun Array<TdApi.PhotoSize>.largestByArea(): TdApi.PhotoSize? =
        filter { it.photo != null }.maxByOrNull { areaOf(it) }

    private fun areaOf(size: TdApi.PhotoSize): Long = size.width.toLong() * size.height.toLong()
}
