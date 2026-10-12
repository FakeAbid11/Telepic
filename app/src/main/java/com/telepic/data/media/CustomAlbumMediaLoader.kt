package com.telepic.data.media

import com.telepic.domain.media.LocalMedia

/**
 * Pages a **user-created** album's members.
 *
 * Two things make this different from [MediaStoreMediaLoader]. The member set lives in Room rather than
 * in MediaStore, so it has to be read and then resolved; and SQLite bounds how many `?` placeholders a
 * single `IN (...)` may carry (999 on the older devices Telepic supports), so a large album cannot be
 * fetched in one query.
 *
 * So the members are resolved once per paging generation, in chunks, and merged into a single
 * newest-first sequence with [MediaSortOrder.TIMELINE_DESC_COMPARATOR] — the same ordering the timeline
 * and the folder grids use, so an album is never in a different order from the library it was drawn
 * from. Each page then fetches only its own slice, which keeps the per-page cost bounded by the page
 * size instead of by the album size.
 *
 * [hiddenIdsProvider] is applied here for the same reason the other loaders apply it: archiving or
 * trashing a photo has to take it out of *every* grid, and an album is not an exception. Because the
 * whole ordered id list is materialised first, the exclusion is exact and the paging offsets stay
 * correct — no truncated `NOT IN (...)` and no page that returns short.
 *
 * [orderedIds] is deliberately never invalidated: a fresh loader is built for each paging generation
 * (`MediaPagingSource` is recreated on invalidation), so the cache lives exactly as long as the
 * generation it describes.
 */
class CustomAlbumMediaLoader(
    private val memberIdsProvider: suspend () -> List<Long>,
    private val lookup: LocalMediaLookup,
    private val hiddenIdsProvider: suspend () -> Set<Long> = { emptySet() },
    private val queryChunkSize: Int = MAX_IDS_PER_QUERY,
) : MediaPageLoader {

    private var orderedIds: List<Long>? = null

    override suspend fun load(offset: Int, limit: Int): List<LocalMedia> {
        val ids = orderedIds ?: loadOrderedIds().also { orderedIds = it }
        if (offset >= ids.size) return emptyList()
        // The slice is at most `limit` ids, so this second query is always far inside the bound.
        val page = ids.subList(offset, minOf(offset + limit, ids.size))
        return lookup.byIdList(page)
    }

    private suspend fun loadOrderedIds(): List<Long> {
        val memberIds = memberIdsProvider()
        if (memberIds.isEmpty()) return emptyList()
        // Archived and trashed media is dropped *before* the lookup, so a filed-away photo does not
        // occupy a page slot in an album any more than it does in the timeline or a folder album.
        // Filtering here rather than after the query is also what keeps the offsets consistent.
        val hidden = hiddenIdsProvider()
        val visible = if (hidden.isEmpty()) memberIds else memberIds.filterNot { it in hidden }
        if (visible.isEmpty()) return emptyList()
        // Each chunk comes back already ordered by the provider; the merge is what restores the order
        // *between* chunks, which is the part a per-chunk sort cannot do on its own.
        val resolved = visible.chunked(queryChunkSize).flatMap { chunk -> lookup.byIdList(chunk) }
        return resolved
            .sortedWith(MediaSortOrder.TIMELINE_DESC_COMPARATOR)
            .map { it.id }
    }

    private companion object {
        /** Comfortably under SQLite's classic 999-variable ceiling, and at the lookup's own cap. */
        const val MAX_IDS_PER_QUERY = 500
    }
}
