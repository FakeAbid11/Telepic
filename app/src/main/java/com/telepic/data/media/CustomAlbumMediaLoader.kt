package com.telepic.data.media

import com.telepic.domain.media.LocalMedia

/** Comfortably under SQLite's classic 999-variable ceiling, and at the lookup's own cap. */
const val MAX_IDS_PER_QUERY = 500

/**
 * Resolves a set of user-album member ids to the ones that still exist, ordered exactly like the
 * timeline.
 *
 * Album membership lives in Room, but the rows to display come from MediaStore — and the two cannot be
 * joined. So the member ids are resolved in chunks (SQLite bounds how many `?` placeholders a single
 * `IN (...)` may carry) and merged into one newest-first sequence with
 * [MediaSortOrder.TIMELINE_DESC_COMPARATOR], the Kotlin mirror of the SQL ordering.
 *
 * [hiddenIds] (archived + trashed) is dropped *before* the lookup: a filed-away photo must not occupy a
 * slot in an album any more than it does in the timeline, and filtering up front is what keeps the
 * paging offsets consistent rather than letting a page return short.
 *
 * Shared by the contents pager and the Viewer's neighbour walk, so an album's grid, its swiping and its
 * day headers can never disagree about what it contains or in what order.
 */
suspend fun resolveOrderedMemberIds(
    memberIds: List<Long>,
    lookup: LocalMediaLookup,
    hiddenIds: Set<Long> = emptySet(),
    queryChunkSize: Int = MAX_IDS_PER_QUERY,
): List<Long> {
    if (memberIds.isEmpty()) return emptyList()
    val visible = if (hiddenIds.isEmpty()) memberIds else memberIds.filterNot { it in hiddenIds }
    if (visible.isEmpty()) return emptyList()
    // Each chunk comes back already ordered by the provider; the merge is what restores the order
    // *between* chunks, which is the part a per-chunk sort cannot do on its own.
    val resolved = visible.chunked(queryChunkSize).flatMap { chunk -> lookup.byIdList(chunk) }
    return resolved
        .sortedWith(MediaSortOrder.TIMELINE_DESC_COMPARATOR)
        .map { it.id }
}

/**
 * Pages a **user-created** album's members.
 *
 * The member set is resolved once per paging generation and then sliced, so each page's cost is bounded
 * by the page size rather than by the album size.
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
        val ids = orderedIds ?: resolveOrderedMemberIds(
            memberIds = memberIdsProvider(),
            lookup = lookup,
            hiddenIds = hiddenIdsProvider(),
            queryChunkSize = queryChunkSize,
        ).also { orderedIds = it }
        if (offset >= ids.size) return emptyList()
        // The slice is at most `limit` ids, so this second query is always far inside the bound.
        val page = ids.subList(offset, minOf(offset + limit, ids.size))
        return lookup.byIdList(page)
    }
}
