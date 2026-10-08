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

    /** The id of the adjacent item in [direction], or null at the ends of the collection. */
    suspend fun neighborId(id: Long, direction: NeighborDirection): Long?
}

class MediaStoreLocalLookup(context: Context) : LocalMediaLookup {

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

    override suspend fun neighborId(id: Long, direction: NeighborDirection): Long? = withContext(Dispatchers.IO) {
        val cmp = if (direction == NeighborDirection.OLDER) "<" else ">"
        val order = if (direction == NeighborDirection.OLDER) "_ID DESC" else "_ID ASC"
        queryOne(
            selection = "$mediaTypeSelection AND ${MediaStore.MediaColumns._ID} $cmp ?",
            args = arrayOf(id.toString()),
            sortOrder = order,
        )?.id
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
}

/** A no-op lookup used when the caller cannot supply a real MediaStore source (keeps Viewer safe). */
object EmptyLocalMediaLookup : LocalMediaLookup {
    override suspend fun byId(id: Long): LocalMedia? = null
    override suspend fun neighborId(id: Long, direction: NeighborDirection): Long? = null
}
