package com.telepic.navigation

/**
 * The browsing context a local Viewer session was opened from, which fixes the order next/previous
 * follow. Without it the Viewer always walked the global timeline, so tapping an item inside an album
 * or the Favorites/Archive/Trash collections and swiping would leave that collection.
 */
sealed interface ViewerScope {
    /** The whole (visible) library in MediaStore chronology — used when opened from Photos/Map. */
    data object Global : ViewerScope

    /** One MediaStore bucket — used when opened from an album's contents. */
    data class Bucket(val bucketId: Long) : ViewerScope

    /** A curated collection (its own Room id-set, newest-first) — opened from Favorites/Archive/Trash. */
    data class Collection(val kind: OrganizationKind) : ViewerScope
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

    // mediaId is required; bucketId/orgKind are optional query args (defaults registered in the nav
    // graph) so a plain local route still matches while albums/collections pass their context.
    const val LOCAL = "viewer/local/{$ARG_MEDIA_ID}?{$ARG_BUCKET_ID}&{$ARG_ORG_KIND}"
    const val CLOUD = "viewer/cloud/{$ARG_CHAT_ID}/{$ARG_MESSAGE_ID}"

    fun local(mediaId: Long): String = "viewer/local/$mediaId"

    /** Opens the Viewer scoped to one album bucket so next/previous stay inside it. */
    fun localInBucket(mediaId: Long, bucketId: Long): String =
        "viewer/local/$mediaId?$ARG_BUCKET_ID=$bucketId"

    /** Opens the Viewer scoped to a curated collection so next/previous follow that set's order. */
    fun localInCollection(mediaId: Long, kind: OrganizationKind): String =
        "viewer/local/$mediaId?$ARG_ORG_KIND=${kind.name.lowercase()}"

    fun cloud(chatId: Long, messageId: Long): String = "viewer/cloud/$chatId/$messageId"

    /** Reconstructs the typed [MediaSource] from a local-route back stack entry. */
    fun localSourceOf(mediaId: Long): MediaSource = MediaSource.Local(mediaId)

    /** Reconstructs the typed [MediaSource] from a cloud-route back stack entry. */
    fun cloudSourceOf(chatId: Long, messageId: Long): MediaSource = MediaSource.Cloud(chatId, messageId)

    /** Rebuilds the originating [ViewerScope] from the back stack args, defaulting to the global timeline. */
    fun scopeOf(bucketId: Long?, orgKind: OrganizationKind?): ViewerScope = when {
        orgKind != null -> ViewerScope.Collection(orgKind)
        bucketId != null && bucketId >= 0L -> ViewerScope.Bucket(bucketId)
        else -> ViewerScope.Global
    }
}
