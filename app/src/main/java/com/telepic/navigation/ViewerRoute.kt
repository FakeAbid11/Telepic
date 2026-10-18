package com.telepic.navigation

/**
 * The browsing context a local Viewer session was opened from, which fixes the order next/previous
 * follow. Without it the Viewer always walked the global timeline, so tapping an item inside an album
 * or the Favorites/Archive/Trash collections and swiping would leave that collection.
 */
sealed interface ViewerScope {
    /** The whole (visible) library in MediaStore chronology — used when opened from Photos/Map. */
    data object Global : ViewerScope

    /** One MediaStore bucket — used when opened from a folder album's contents. */
    data class Bucket(val bucketId: Long) : ViewerScope

    /** A curated collection (its own Room id-set, newest-first) — opened from Favorites/Archive/Trash. */
    data class Collection(val kind: OrganizationKind) : ViewerScope

    /**
     * A user-created album (its own Room id-set) — opened from an album the user made.
     *
     * Its own scope rather than reusing [Collection]: the two answer different questions. A collection
     * asks "what is favourited?", which the organization repository already knows; an album asks "what
     * is in *this* album?", which is a different table entirely.
     */
    data class Album(val albumId: Long) : ViewerScope
}

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
    const val ARG_BUCKET_ID = "bucketId"
    const val ARG_ORG_KIND = "orgKind"
    const val ARG_ALBUM_ID = "albumId"

    // The one place the viewer namespace is spelled. LOCAL, CLOUD and every builder derive from it,
    // and the app shell fades its chrome by matching this same prefix against the current route
    // (see TelepicApp) — so the patterns and the shell's match cannot drift apart. No tab route
    // starts with it, which is what keeps the fade from ever firing on a top-level destination.
    const val PREFIX = "viewer/"

    // mediaId is required; the rest are optional query args (defaults registered in the nav graph) so
    // a plain local route still matches while albums and collections pass their own context. Exactly
    // one of them is ever supplied, and [scopeOf] fixes the precedence.
    //
    // Each query arg must keep its `key={key}` shape. Navigation only reads a named query value when
    // the pattern spells the key out; a bare `?{key}` is instead its "single query param value only"
    // form, which hands the *entire* raw query string to every declared arg — Longs then fail to parse
    // back to their defaults and the scope collapses to [ViewerScope.Global].
    const val LOCAL =
        "${PREFIX}local/{$ARG_MEDIA_ID}?$ARG_BUCKET_ID={$ARG_BUCKET_ID}" +
            "&$ARG_ORG_KIND={$ARG_ORG_KIND}&$ARG_ALBUM_ID={$ARG_ALBUM_ID}"
    const val CLOUD = "${PREFIX}cloud/{$ARG_CHAT_ID}/{$ARG_MESSAGE_ID}"

    fun local(mediaId: Long): String = "${PREFIX}local/$mediaId"

    /** Opens the Viewer scoped to one album bucket so next/previous stay inside it. */
    fun localInBucket(mediaId: Long, bucketId: Long): String =
        "${PREFIX}local/$mediaId?$ARG_BUCKET_ID=$bucketId"

    /** Opens the Viewer scoped to a curated collection so next/previous follow that set's order. */
    fun localInCollection(mediaId: Long, kind: OrganizationKind): String =
        "${PREFIX}local/$mediaId?$ARG_ORG_KIND=${kind.name.lowercase()}"

    /** Opens the Viewer scoped to a user-created album so next/previous stay inside that album. */
    fun localInCustomAlbum(mediaId: Long, albumId: Long): String =
        "${PREFIX}local/$mediaId?$ARG_ALBUM_ID=$albumId"

    fun cloud(chatId: Long, messageId: Long): String = "${PREFIX}cloud/$chatId/$messageId"

    /**
     * Rebuilds the originating [ViewerScope] from the back stack args, defaulting to the global
     * timeline.
     *
     * The order is a precedence, not a fallback chain: a route carries at most one of the three, and
     * naming the winner makes the behaviour explicit instead of dependent on which check came first.
     */
    fun scopeOf(
        bucketId: Long?,
        orgKind: OrganizationKind?,
        albumId: Long? = null,
    ): ViewerScope = when {
        albumId != null && albumId >= 0L -> ViewerScope.Album(albumId)
        orgKind != null -> ViewerScope.Collection(orgKind)
        bucketId != null && bucketId >= 0L -> ViewerScope.Bucket(bucketId)
        else -> ViewerScope.Global
    }
}
