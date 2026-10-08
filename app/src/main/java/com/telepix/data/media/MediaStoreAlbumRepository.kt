package com.telepix.data.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.paging.Pager
import androidx.paging.PagingConfig
import com.telepix.domain.media.Album
import com.telepix.domain.media.LocalMedia
import com.telepix.domain.media.PhotosItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * [AlbumRepository] over MediaStore. Albums are grouped by `BUCKET_ID` from a light, projection-
 * limited, newest-first query — never a full-library load of every media record. Bucket contents
 * reuse the exact Photos paging pipeline ([MediaPagingSource] + [MediaStoreMediaMapper]), filtered
 * by bucket, so an album grid behaves identically to the timeline.
 */
class MediaStoreAlbumRepository(context: Context) : AlbumRepository {

    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver

    // Grouping columns only — deliberately not the full media projection.
    private val albumProjection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
    )

    private val mediaSelection =
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"

    override suspend fun albums(): List<Album> = withContext(Dispatchers.IO) {
        val order = "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns._ID} DESC"
        val rows = ArrayList<AlbumGrouper.Row>()
        try {
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                albumProjection,
                mediaSelection,
                null,
                order,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val takenCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                val modCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val bucketCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                if (bucketCol < 0) return@use
                while (cursor.moveToNext()) {
                    val bucketId = if (cursor.isNull(bucketCol)) null else cursor.getLong(bucketCol)
                    val taken = if (takenCol >= 0 && !cursor.isNull(takenCol)) cursor.getLong(takenCol) else 0L
                    val mod = if (modCol >= 0 && !cursor.isNull(modCol)) cursor.getLong(modCol) else 0L
                    rows += AlbumGrouper.Row(
                        id = cursor.getLong(idCol),
                        bucketId = bucketId,
                        bucketName = if (bucketNameCol >= 0) cursor.getString(bucketNameCol) else null,
                        mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) else null,
                        whenMillis = if (taken > 0L) taken else mod * 1000L,
                    )
                }
            }
        } catch (_: Throwable) {
            return@withContext emptyList()
        }
        AlbumGrouper.group(rows) { id, mime -> MediaStoreMediaMapper.contentUriFor(id, mime) }
    }

    override fun mediaInBucket(bucketId: Long): Flow<PagingData<PhotosItem>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            prefetchDistance = PREFETCH_DISTANCE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = { MediaPagingSource(MediaStoreBucketLoader(appContext, bucketId)) },
    ).flow

    private data class AlbumSeed(val uri: Uri, val isVideo: Boolean, val latest: Long)

    private companion object {
        const val PAGE_SIZE = 60        const val PREFETCH_DISTANCE = 30
        const val INITIAL_LOAD_SIZE = 90
    }
}

/** A bucket-scoped [MediaPageLoader] — the same projection/order as the timeline, filtered to one bucket. */
class MediaStoreBucketLoader(context: Context, private val bucketId: Long) : MediaPageLoader {

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

    override suspend fun load(offset: Int, limit: Int): List<LocalMedia> = withContext(Dispatchers.IO) {
        val scope: CoroutineScope = this
        val realSelection =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})" +
                " AND ${MediaStore.MediaColumns.BUCKET_ID} = ?"
        val results = ArrayList<LocalMedia>(limit)
        contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            realSelection,
            arrayOf(bucketId.toString()),
            "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns._ID} DESC",
        )?.use { cursor ->
            if (!cursor.moveToPosition(offset - 1)) return@use
            var count = 0
            while (count < limit && cursor.moveToNext()) {
                scope.ensureActive()
                val row = CursorMediaRow(cursor)
                val id = row.long(MediaStore.MediaColumns._ID) ?: continue
                val mime = row.string(MediaStore.MediaColumns.MIME_TYPE)
                results += MediaStoreMediaMapper.map(row, MediaStoreMediaMapper.contentUriFor(id, mime))
                count++
            }
        }
        results
    }
}
