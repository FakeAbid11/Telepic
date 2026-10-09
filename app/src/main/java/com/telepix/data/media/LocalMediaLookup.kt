package com.telepix.data.media

import android.content.Context
import android.provider.MediaStore
import com.telepix.domain.media.LocalMedia
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
     */
    suspend fun neighborId(id: Long, direction: NeighborDirection, bucketId: Long? = null): Long?

    /**
     * Resolve many items in a single bounded `_ID IN (...)` query (used by Favorites / Archive /
     * Trash, whose id-sets come from Room). Missing/inaccessible ids are simply absent from the
     * result, so an item deleted outside the app never crashes a screen. Order is newest-first.
     */
    suspend fun byIdList(ids: Collection<Long>): List<LocalMedia>
}

class MediaStoreLocalLookup(
    context: Context,
    private val hiddenIdsProvider: suspend () -> Set<Long> = { emptySet() },
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
            val cmp = if (direction == NeighborDirection.OLDER) "<" else ">"
            val order = if (direction == NeighborDirection.OLDER) "_ID DESC" else "_ID ASC"
            // Skip media the user archived/trashed so a swipe never lands on a hidden item, and stay
            // inside the requested bucket when one is given (album context).
            val hidden = hiddenIdsProvider().take(MAX_LOOKUP_IDS)
            val sb = StringBuilder("$mediaTypeSelection AND ${MediaStore.MediaColumns._ID} $cmp ?")
            val args = ArrayList<String>()
            args += id.toString()
            if (bucketId != null) {
                sb.append(" AND ${MediaStore.MediaColumns.BUCKET_ID} = ?")
                args += bucketId.toString()
            }
            if (hidden.isNotEmpty()) {
                sb.append(" AND ${MediaStore.MediaColumns._ID} NOT IN (${hidden.joinToString(",") { "?" }})")
                hidden.forEach { args += it.toString() }
            }
            queryOne(selection = sb.toString(), args = args.toTypedArray(), sortOrder = order)?.id
        }

    override suspend fun byIdList(ids: Collection<Long>): List<LocalMedia> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val bounded = ids.take(MAX_LOOKUP_IDS)
        val placeholders = bounded.joinToString(",") { "?" }
        val selection = "$mediaTypeSelection AND ${MediaStore.MediaColumns._ID} IN ($placeholders)"
        try {
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                bounded.map { it.toString() }.toTypedArray(),
                "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns._ID} DESC",
            )?.use { cursor ->
                val out = ArrayList<LocalMedia>(bounded.size)
                while (cursor.moveToNext()) {
                    val row = CursorMediaRow(cursor)
                    val id = row.long(MediaStore.MediaColumns._ID) ?: continue
                    val mime = row.string(MediaStore.MediaColumns.MIME_TYPE)
                    out += MediaStoreMediaMapper.map(row, MediaStoreMediaMapper.contentUriFor(id, mime))
                }
                out
            } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
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
        } catch (_: Throwable) {
            null
        }

    private companion object {
        const val MAX_LOOKUP_IDS = 500
    }
}

/** A no-op lookup used when the caller cannot supply a real MediaStore source (keeps Viewer safe). */
object EmptyLocalMediaLookup : LocalMediaLookup {
    override suspend fun byId(id: Long): LocalMedia? = null
    override suspend fun neighborId(id: Long, direction: NeighborDirection, bucketId: Long?): Long? = null
    override suspend fun byIdList(ids: Collection<Long>): List<LocalMedia> = emptyList()
}
