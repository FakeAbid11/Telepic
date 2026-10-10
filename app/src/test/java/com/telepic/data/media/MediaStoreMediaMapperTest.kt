package com.telepic.data.media

import android.net.Uri
import android.provider.MediaStore
import com.telepic.domain.media.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the row → domain mapping with a simple fake [MediaRow], including optional-column
 * handling and video duration. Runs under Robolectric so real [Uri] values are available.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaStoreMediaMapperTest {

    private class FakeRow(private val values: Map<String, Any?>) : MediaRow {
        override fun hasColumn(name: String): Boolean = values.containsKey(name)
        override fun string(name: String): String? = values[name] as String?
        override fun long(name: String): Long? = (values[name] as? Number)?.toLong()
        override fun int(name: String): Int? = (values[name] as? Number)?.toInt()
    }

    private val uri = Uri.parse("content://media/external/images/media/7")

    @Test
    fun `maps photo fields and prefers date taken`() {
        val row = FakeRow(
            mapOf(
                MediaStore.MediaColumns._ID to 7L,
                MediaStore.MediaColumns.DISPLAY_NAME to "a.jpg",
                MediaStore.MediaColumns.MIME_TYPE to "image/jpeg",
                MediaStore.MediaColumns.DATE_TAKEN to 1_700_000_000_000L,
                MediaStore.MediaColumns.DATE_MODIFIED to 1_699_999_999L,
                MediaStore.MediaColumns.SIZE to 2048L,
                MediaStore.MediaColumns.WIDTH to 100,
                MediaStore.MediaColumns.HEIGHT to 200,
                MediaStore.MediaColumns.BUCKET_ID to 5L,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME to "Camera",
                MediaStore.MediaColumns.RELATIVE_PATH to "DCIM/Camera/",
            ),
        )

        val media = MediaStoreMediaMapper.map(row, uri)

        assertEquals(7L, media.id)
        assertEquals(MediaType.PHOTO, media.type)
        assertEquals(uri, media.contentUri)
        assertEquals("a.jpg", media.displayName)
        assertEquals(1_700_000_000_000L, media.dateMillis)
        assertNull(media.durationMillis)
        assertEquals(2048L, media.sizeBytes)
        assertEquals(100, media.width)
        assertEquals(200, media.height)
        assertEquals(5L, media.bucketId)
        assertEquals("Camera", media.bucketName)
        assertEquals("DCIM/Camera/", media.relativePath)
    }

    @Test
    fun `maps video with duration and modified-date fallback`() {
        val row = FakeRow(
            mapOf(
                MediaStore.MediaColumns._ID to 9L,
                MediaStore.MediaColumns.MIME_TYPE to "video/mp4",
                MediaStore.MediaColumns.DATE_TAKEN to 0L,
                MediaStore.MediaColumns.DATE_MODIFIED to 1_600_000_000L,
                MediaStore.Video.Media.DURATION to 24_000L,
            ),
        )

        val media = MediaStoreMediaMapper.map(row, uri)

        assertEquals(MediaType.VIDEO, media.type)
        assertEquals(1_600_000_000_000L, media.dateMillis)
        assertEquals(24_000L, media.durationMillis)
    }

    @Test
    fun `gif maps to gif type and has no duration`() {
        val row = FakeRow(
            mapOf(
                MediaStore.MediaColumns._ID to 3L,
                MediaStore.MediaColumns.MIME_TYPE to "image/gif",
                MediaStore.MediaColumns.DATE_TAKEN to 5_000L,
            ),
        )
        val media = MediaStoreMediaMapper.map(row, uri)
        assertEquals(MediaType.GIF, media.type)
        assertNull(media.durationMillis)
    }

    @Test
    fun `missing optional columns map to null or zero safely`() {
        val row = FakeRow(
            mapOf(
                MediaStore.MediaColumns._ID to 1L,
                MediaStore.MediaColumns.MIME_TYPE to "image/png",
            ),
        )
        val media = MediaStoreMediaMapper.map(row, uri)
        assertEquals(0L, media.dateMillis)
        assertEquals(0L, media.sizeBytes)
        assertEquals(0, media.width)
        assertNull(media.bucketName)
        assertNull(media.relativePath)
    }

    @Test
    fun `content uri picks the right collection and keeps a stable id`() {
        val videoUri = MediaStoreMediaMapper.contentUriFor(42L, "video/mp4")
        val expectedVideo = android.content.ContentUris.withAppendedId(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            42L,
        )
        assertEquals(expectedVideo, videoUri)
        assertEquals(42L, android.content.ContentUris.parseId(videoUri))

        val imageUri = MediaStoreMediaMapper.contentUriFor(7L, "image/jpeg")
        val expectedImage = android.content.ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            7L,
        )
        assertEquals(expectedImage, imageUri)
    }
}
