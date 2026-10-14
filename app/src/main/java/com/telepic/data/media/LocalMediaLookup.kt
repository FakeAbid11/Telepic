package com.telepic.data.media

import android.content.Context
import android.provider.MediaStore
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.media.LocalMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Direction for viewer neighbor lookups within the local library. */
enum class NeighborDirection { OLDER, NEWER }

/**
 * Single-item + neighbor lookups the Viewer needs, kept separate from the paged [MediaPageLoader]
 * so the paging contract (and its fakes) stay unchanged. Cheap, bounded, provider-side queries —
 * never a full-library scan — and identity/order follow the same MediaStore `_ID` chronology the
 * grid uses, so a resolved neighbor is always the genuinely adjacent item.
 */
interface LocalMediaLookup {
    /** Resolve a single item by its stable MediaStore id, or null when it is gone/inaccessible. */
    suspend fun byId(id: Long): LocalMedia?

    /**
     * The id of the adjacent item in [direction], or null at the ends of the collection.
     *
     * Hidden (archived/trashed) ids are excluded so the Viewer never lands on media the user has filed
     * away, consistent with the timeline and album grids. When [bucketId] is supplied the search stays
     * within that one bucket, so an album's Viewer wraps around its own contents instead of the library.
     *
     * The exclusion is exact at any archive/trash size (see [walkToVisibleNeighbor]); if an unbroken
     * run of hidden items is longer than the walk's budget, the result is null — "no neighbour" —
     * rather than an item the user filed away.
     */
    suspend fun neighborId(id: Long, direction: NeighborDirection, bucketId: Long? = null): Long?

    /**
     * Resolve many items by id (used by Favorites / Archive / Trash, whose id-sets come from Room).
     * Missing/inaccessible ids are simply absent from the result, so an item deleted outside the app
     * never crashes a screen. Order is newest-first, and **every** id in [ids] is resolved: SQLite bounds
     * how many placeholders one query can carry, so collections past that bound are read in chunks and
     * merged rather than truncated.
     *
     * **A failed query propagates.** It used to swallow the throwable, which made a provider that
     * refused to answer indistinguishable from a collection with nothing in it — and a curated screen
     * then reported "Trash is empty" over a read that never happened. Callers that genuinely want a
     * best-effort answer (an album cover, a swipe neighbour) wrap this themselves and say so; the
     * album pager lets it reach `LoadResult.Error`, which is what turns it into a retryable state.
     */
    suspend fun byIdList(ids: Collection<Long>): List<LocalMedia>
}

class MediaStoreLocalLookup(
    context: Context,
    private val hiddenIdsProvider: suspend () -> Set<Long> = { emptySet() },
    private val typeFilterProvider: suspend () -> TimelineMediaFilter = { TimelineMediaFilter.All },
) : LocalMediaLookup {

    private val contentResolver = context.applicationContext.contentResolver

    private val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.Video.Media.DURATION,
    )

    private val mediaTypeSelection =
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"

    override suspend fun byId(id: Long): LocalMedia? = withContext(Dispatchers.IO) {
        queryOne(
            selection = "$mediaTypeSelection AND ${MediaStore.MediaColumns._ID} = ?",
            args = arrayOf(id.toString()),
            sortOrder = null,
        )
    }

    override suspend fun neighborId(id: Long, direction: NeighborDirection, bucketId: Long?): Long? =
        withContext(Dispatchers.IO) {
            val hidden = hiddenIdsProvider()
            // The neighbour walk honours the hide-videos / hide-GIFs preferences so a swipe can never
            // land on a kind the grid refuses to show. byId/byIdList deliberately do NOT: an item
            // opened by id, or listed in a Favorites/Trash collection, must still resolve.
            val filter = typeFilterProvider()
            walkToVisibleNeighbor(id, hidden) { cursor ->
                queryNeighbor(cursor, direction, bucketId, filter)
            }
        }

    /** The nearest neighbour of [id] in [direction], ignoring hidden state; null at the collection end. */
    private fun queryNeighbor(
        id: Long,
        direction: NeighborDirection,
        bucketId: Long?,
        filter: TimelineMediaFilter,
    ): Long? {
        val cmp = if (direction == NeighborDirection.OLDER) "<" else ">"
        val order = if (direction == NeighborDirection.OLDER) "_ID DESC" else "_ID ASC"
        val sb = StringBuilder("$mediaTypeSelection AND ${MediaStore.MediaColumns._ID} $cmp ?")
        val args = ArrayList<String>()
        args += id.toString()
        if (bucketId != null) {
            sb.append(" AND ${MediaStore.MediaColumns.BUCKET_ID} = ?")
            args += bucketId.toString()
        }
        // No bound arguments (a constant MIME literal), so appending cannot disturb the arg order.
        sb.append(filter.selectionSuffix())
        return queryOne(selection = sb.toString(), args = args.toTypedArray(), sortOrder = order)?.id
    }

    override suspend fun byIdList(ids: Collection<Long>): List<LocalMedia> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        if (ids.size <= MAX_LOOKUP_IDS) return@withContext queryByIds(ids)
        // Past the placeholder bound the chunks are the only honest option: this used to `take()` the
        // first 500 ids and return them as if that were the whole collection, so a Favorites or Trash
        // list longer than the cap came back short and *confident* — the screen showed 500 items and
        // never said the rest were not there. Merging the chunks restores the newest-first order across
        // them, which a per-chunk sort cannot do on its own. A failing chunk still propagates, keeping
        // the contract on LocalMediaLookup.byIdList.
        ids.chunked(MAX_LOOKUP_IDS)
            .flatMap { queryByIds(it) }
            .sortedWith(MediaSortOrder.TIMELINE_DESC_COMPARATOR)
    }

    /** One bounded `_ID IN (...)` read. Callers must keep [ids] within [MAX_LOOKUP_IDS]. */
    private suspend fun queryByIds(ids: Collection<Long>): List<LocalMedia> {
        val placeholders = ids.joinToString(",") { "?" }
        val selection = "$mediaTypeSelection AND ${MediaStore.MediaColumns._ID} IN ($placeholders)"
        // No try/catch: a failed read must be distinguishable from an empty collection. See the
        // contract on LocalMediaLookup.byIdList.
        return contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            ids.map { it.toString() }.toTypedArray(),
            // Same coalesced newest-first order the timeline displays, so a curated collection
            // (favorites/archive/trash) steps in exactly the order the user sees.
            MediaSortOrder.TIMELINE_DESC,
        )?.use { cursor ->
            val out = ArrayList<LocalMedia>(ids.size)
            while (cursor.moveToNext()) {
                val row = CursorMediaRow(cursor)
                val id = row.long(MediaStore.MediaColumns._ID) ?: continue
                val mime = row.string(MediaStore.MediaColumns.MIME_TYPE)
                out += MediaStoreMediaMapper.map(row, MediaStoreMediaMapper.contentUriFor(id, mime))
            }
            out
        } ?: emptyList()
    }

    private fun queryOne(selection: String, args: Array<String>, sortOrder: String?): LocalMedia? =
        try {
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                args,
                sortOrder,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val row = CursorMediaRow(cursor)
                val id = row.long(MediaStore.MediaColumns._ID) ?: return null
                val mime = row.string(MediaStore.MediaColumns.MIME_TYPE)
                MediaStoreMediaMapper.map(row, MediaStoreMediaMapper.contentUriFor(id, mime))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // The caller reports the item as missing either way; the log is what separates "the row is
            // gone" from "the provider refused to answer".
            TelepicLog.warn("MediaLookup", "could not read the requested ids", throwable)
            null
        }

    private companion object {
        const val MAX_LOOKUP_IDS = 500
    }
}

/**
 * Walks away from [startId], skipping every id in [hidden], and returns the first visible one.
 *
 * The hidden set is applied **here, in memory**, rather than as a SQL `NOT IN (...)`. A placeholder
 * list has to be truncated to stay inside SQLite's bound on query parameters, and a truncated list
 * silently let the Viewer swipe onto media the user had archived or trashed — the cap made the
 * exclusion wrong exactly when it mattered most. A [Set] has no such limit, so the exclusion is exact
 * however large the archive/trash grows.
 *
 * [next] yields the nearest not-yet-examined id. Returns null at the end of the collection, and also
 * when [maxSteps] consecutive candidates were all hidden: reporting "no neighbour" is honest, whereas
 * returning a hidden id would defeat the point of hiding it.
 */
internal suspend fun walkToVisibleNeighbor(
    startId: Long,
    hidden: Set<Long>,
    maxSteps: Int = MAX_NEIGHBOR_SKIPS,
    next: suspend (Long) -> Long?,
): Long? {
    if (hidden.isEmpty()) return next(startId)
    var cursor = startId
    repeat(maxSteps) {
        val candidate = next(cursor) ?: return null
        if (candidate !in hidden) return candidate
        cursor = candidate
    }
    return null
}

/**
 * How many consecutive hidden items one neighbour lookup steps past before giving up. Each step is a
 * single indexed `_ID` query, so this bounds the work at a swipe to something a user would not notice
 * — and the fallback is "no neighbour", never a hidden item.
 */
internal const val MAX_NEIGHBOR_SKIPS = 64
