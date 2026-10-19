package com.telepic.data.media

/**
 * The paging sources a repository currently has alive.
 *
 * Every library view is served by a `Pager`, and a `cachedIn` pager never re-queries on its own — so
 * a change that the user must see immediately (a photo removed from an album, the library's
 * media-kind filter changed) has to *invalidate* the pager explicitly.
 *
 * The invalidation used to reach only the **most recently created** source, because that was the only
 * one the repository remembered. With two pagers alive at once — the Photos timeline and an open
 * album, say — the refresh was handed to whichever was created last, and the other silently kept
 * serving the old data. That is why hiding videos changed the Albums screen but not the timeline, and
 * why removing a photo from an album left it in the album's grid.
 *
 * The registry remembers every live source and invalidates them all, which is what "refresh" means
 * to every caller. A source Paging has already invalidated is dropped, so the registry does not grow
 * with every search term the user types.
 */
class PagingSourceRegistry {

    private val sources = mutableSetOf<MediaPagingSource>()

    /** Remember [source] and forget it again once Paging invalidates it. */
    fun track(source: MediaPagingSource) {
        synchronized(sources) { sources += source }
        // Registered on a source that is already invalidated would throw, so this has to happen
        // while the source is fresh — which it is: it was just created.
        source.registerInvalidatedCallback { forget(source) }
    }

    /** Invalidate every live source, so the next load re-reads the underlying data. */
    fun invalidateAll() {
        val live = synchronized(sources) { sources.toList() }
        live.forEach { it.invalidate() }
    }

    private fun forget(source: MediaPagingSource) {
        synchronized(sources) { sources -= source }
    }
}
