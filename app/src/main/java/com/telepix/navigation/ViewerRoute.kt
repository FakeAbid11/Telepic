package com.telepix.navigation

/**
 * Navigation contract for the media viewer. Only stable identifiers travel through navigation —
 * never a bitmap, a content path, or a conflated id. The route encodes the *source* explicitly so a
 * Telegram (chatId, messageId) is never mistaken for a local MediaStore id (and vice versa), and so
 * back navigation returns to the originating context.
 */
object ViewerRoute {
    const val ARG_MEDIA_ID = "mediaId"
    const val ARG_CHAT_ID = "chatId"
    const val ARG_MESSAGE_ID = "messageId"

    const val LOCAL = "viewer/local/{$ARG_MEDIA_ID}"
    const val CLOUD = "viewer/cloud/{$ARG_CHAT_ID}/{$ARG_MESSAGE_ID}"

    fun local(mediaId: Long): String = "viewer/local/$mediaId"

    fun cloud(chatId: Long, messageId: Long): String = "viewer/cloud/$chatId/$messageId"

    /** Reconstructs the typed [MediaSource] from a local-route back stack entry. */
    fun localSourceOf(mediaId: Long): MediaSource = MediaSource.Local(mediaId)

    /** Reconstructs the typed [MediaSource] from a cloud-route back stack entry. */
    fun cloudSourceOf(chatId: Long, messageId: Long): MediaSource = MediaSource.Cloud(chatId, messageId)
}
