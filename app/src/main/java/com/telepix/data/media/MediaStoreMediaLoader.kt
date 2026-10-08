package com.telepix.data.media

import android.content.Context
import android.os.CancellationSignal
import android.provider.MediaStore
import com.telepix.domain.media.LocalMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Reads the local photo/video library through MediaStore as a single, DB-sorted, paged stream.
 *
 * Uses the [MediaStore.Files] collection so images and videos share one chronological ordering
 * (newest-first) resolved by the provider, instead of loading the whole library into memory and
 * sorting on the device. Only the requested projection columns are read, and only one page is
 * materialized per call. Runs on [Dispatchers.IO] and is cancellation-aware.
 */
class MediaStoreMediaLoader(context: Context) : MediaPageLoader {

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

    // Restrict to images + videos only (excludes audio/documents in the Files table).
    private val selection =
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"

    private val sortOrder =
        "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns._ID} DESC"

    override suspend fun load(offset: Int, limit: Int): List<LocalMedia> =
        withContext(Dispatchers.IO) {
            val scope = this
            val collection = MediaStore.Files.getContentUri("external")
            val cancellation = CancellationSignal()
            val results = ArrayList<LocalMedia>(limit)

            contentResolver.query(
                collection,
                projection,
                selection,
                null,
                sortOrder,
                cancellation,
            )?.use { cursor ->
                // moveToPosition(offset - 1) lands just before the target page; for offset 0 it
                // sits before the first row so the next moveNext() yields the newest item.
                if (!cursor.moveToPosition(offset - 1)) return@use
                var count = 0
                while (count < limit && cursor.moveToNext()) {
                    scope.ensureActive()
                    val row = CursorMediaRow(cursor)
                    val id = row.long(MediaStore.MediaColumns._ID) ?: continue
                    val mimeType = row.string(MediaStore.MediaColumns.MIME_TYPE)
                    val uri = MediaStoreMediaMapper.contentUriFor(id, mimeType)
                    results += MediaStoreMediaMapper.map(row, uri)
                    count++
                }
            }
            results
        }
}
