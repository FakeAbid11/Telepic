package com.telepix.data.media

import android.net.Uri
import android.provider.MediaStore
import com.telepix.domain.media.LocalMedia
import java.util.Locale

/**
 * Maps one MediaStore [MediaRow] into the domain [LocalMedia].
 *
 * Optional / newer columns (RELATIVE_PATH, BUCKET_*, DATE_TAKEN) are read defensively so the
 * mapper never crashes on older devices where a column is absent.
 */
object MediaStoreMediaMapper {

    fun map(row: MediaRow, contentUri: Uri): LocalMedia {
        val id = row.long(MediaStore.MediaColumns._ID) ?: 0L
        val mimeType = row.string(MediaStore.MediaColumns.MIME_TYPE)
        val type = MediaClassifier.classify(mimeType)

        val dateMillis = MediaDateResolver.resolveMillis(
            dateTakenMillis = row.long(MediaStore.MediaColumns.DATE_TAKEN),
            dateModifiedSeconds = row.long(MediaStore.MediaColumns.DATE_MODIFIED),
        )

        val durationMillis = if (type == com.telepix.domain.media.MediaType.VIDEO) {
            row.long(MediaStore.Video.Media.DURATION)?.takeIf { it > 0L }
        } else {
            null
        }

        return LocalMedia(
            id = id,
            contentUri = contentUri,
            type = type,
            mimeType = mimeType,
            displayName = row.string(MediaStore.MediaColumns.DISPLAY_NAME),
            dateMillis = dateMillis,
            durationMillis = durationMillis,
            width = row.int(MediaStore.MediaColumns.WIDTH) ?: 0,
            height = row.int(MediaStore.MediaColumns.HEIGHT) ?: 0,
            sizeBytes = row.long(MediaStore.MediaColumns.SIZE) ?: 0L,
            bucketId = row.long(MediaStore.MediaColumns.BUCKET_ID),
            bucketName = row.string(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
            relativePath = row.string(MediaStore.MediaColumns.RELATIVE_PATH)?.takeIf {
                it.isNotBlank()
            },
        )
    }

    /** Builds the primary content URI for an item based on its (lower-cased) MIME type. */
    fun contentUriFor(id: Long, mimeType: String?): Uri {
        val isVideo = mimeType?.lowercase(Locale.US)?.startsWith("video/") == true
        val base = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return android.content.ContentUris.withAppendedId(base, id)
    }
}
