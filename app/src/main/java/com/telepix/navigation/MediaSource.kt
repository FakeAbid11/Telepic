package com.telepix.navigation

/**
 * A typed description of *what* the Viewer should open and *where it came from*. Local and remote
 * identities are kept explicitly separate — a MediaStore id is never confused with a Telegram
 * (chatId, messageId) pair — so the Viewer never mixes sources and navigation never carries a
 * filesystem path or bitmap, only stable ids.
 */
sealed interface MediaSource {
    /** A local MediaStore item, by its stable id. */
    data class Local(val mediaId: Long) : MediaSource

    /** A Telegram cloud item, by its stable (chatId, messageId) manifest identity. */
    data class Cloud(val chatId: Long, val messageId: Long) : MediaSource
}
