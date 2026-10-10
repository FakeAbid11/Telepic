package com.telepic.data.cloud

import org.drinkless.tdlib.TdApi

/**
 * Constructors for the exact TDLib requests Telepic's cloud layer sends, built against the resolved
 * `io.github.tdlib-android:core:0.1.1` (TDLib 1.8.x) `TdApi` surface — signatures verified from the
 * generated classes, not from another version. Keeping them in one pure place makes the *request
 * shapes* unit-testable on the JVM without a native library or a live session.
 */
object TdApiCloudRequests {

    /** Server-side chat search restricted to channels, used to discover the Telepic destination. */
    fun searchChannels(query: String, limit: Int): TdApi.SearchChatsOnServer =
        TdApi.SearchChatsOnServer(query, TdApi.SearchChatTypeFilterChannel(), limit)

    /**
     * Create the backup destination as a **channel** supergroup (isForum=false, isChannel=true). No
     * location, no auto-delete, not an import — matching a private, media-only backup channel.
     */
    fun createChannel(title: String, description: String): TdApi.CreateNewSupergroupChat =
        TdApi.CreateNewSupergroupChat(title, false, true, description, null, 0, false)

    /** Newest-first history page. `fromMessageId = 0` starts at the newest message. */
    fun history(chatId: Long, fromMessageId: Long, offset: Int, limit: Int): TdApi.GetChatHistory =
        TdApi.GetChatHistory(chatId, fromMessageId, offset, limit, false)

    /** Resolve a chat (for accessibility + title) before trusting it as the destination. */
    fun getChat(chatId: Long): TdApi.GetChat = TdApi.GetChat(chatId)

    /** The account's membership/rights in a chat — the honest basis for "can post". */
    fun getChatMember(chatId: Long, userId: Long): TdApi.GetChatMember =
        TdApi.GetChatMember(chatId, TdApi.MessageSenderUser(userId))

    /**
     * Synchronous file download: TDLib blocks the request until the file is fully downloaded (or
     * fails), returning the [TdApi.File] whose `local.path` is then valid. Used for on-demand
     * originals/previews so we never report a path before the bytes exist.
     */
    fun downloadSynchronously(fileId: Int, priority: Int): TdApi.DownloadFile =
        TdApi.DownloadFile(fileId, priority, 0L, 0L, true)

    /** Ask TDLib for a file's current local/remote state (path, completion). */
    fun getFile(fileId: Int): TdApi.GetFile = TdApi.GetFile(fileId)

    /** Cancel an in-flight download (`onlyIfPending=false` also stops an active download). */
    fun cancelDownload(fileId: Int): TdApi.CancelDownloadFile = TdApi.CancelDownloadFile(fileId, false)

    /** Send media into the destination. Reply/topic/markup/options are all null (a plain post). */
    fun sendMessage(chatId: Long, content: TdApi.InputMessageContent): TdApi.SendMessage =
        TdApi.SendMessage(chatId, null, null, null, null, content)

    /**
     * Re-read a just-sent message to observe its [TdApi.MessageSendingState]. Used to confirm an upload
     * actually succeeded (state clears to null) or failed, by polling rather than racing the update
     * stream — so a queue item is only ever marked backed-up on a confirmed remote send.
     */
    fun getMessage(chatId: Long, messageId: Long): TdApi.GetMessage = TdApi.GetMessage(chatId, messageId)

    /** The authenticated account (used to obtain the current user id for membership checks). */
    fun getMe(): TdApi.GetMe = TdApi.GetMe()
}
