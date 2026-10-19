package com.telepic.navigation

import androidx.compose.runtime.Immutable

/**
 * A typed description of *what* the Viewer should open and *where it came from*. Local and remote
 * identities are kept explicitly separate — a MediaStore id is never confused with a Telegram
 * (chatId, messageId) pair — so the Viewer never mixes sources and navigation never carries a
 * filesystem path or bitmap, only stable ids.
 *
 * [Immutable] because it is passed into composables as a parameter: without it the compiler infers
 * the type is unstable, and every composable taking one is re-run on each recomposition of its
 * parent. The two members are primitives, so the promise holds.
 */
@Immutable
sealed interface MediaSource {
    /** A local MediaStore item, by its stable id. */
    @Immutable
    data class Local(val mediaId: Long) : MediaSource

    /** A Telegram cloud item, by its stable (chatId, messageId) manifest identity. */
    @Immutable
    data class Cloud(val chatId: Long, val messageId: Long) : MediaSource
}
