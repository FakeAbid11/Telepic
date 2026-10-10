package com.telepic.data.media

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MediaStore-bucket grouping: stable identity, newest cover, counts, null-bucket safety, ordering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlbumGrouperTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun uriFor(id: Long, mime: String?) = Uri.parse("content://media/$id")

    private fun row(id: Long, bucket: Long?, name: String?, mime: String?, whenMillis: Long) =
        AlbumGrouper.Row(id, bucket, name, mime, whenMillis)

    @Test
    fun `groups by bucket with counts and the newest cover`() {
        // rows are already newest-first from the provider ordering
        val rows = listOf(
            row(10, 1, "Camera", "image/jpeg", 300L),
            row(11, 1, "Camera", "image/jpeg", 200L),
            row(12, 2, "Screenshots", "image/png", 250L),
        )
        val albums = AlbumGrouper.group(rows) { id, mime -> uriFor(id, mime) }

        assertEquals(2, albums.size)
        // Sorted by newest cover first → bucket 1 (300) precedes bucket 2 (250).
        assertEquals(1L, albums[0].bucketId)
        assertEquals("Camera", albums[0].title)
        assertEquals(2, albums[0].count)
        assertEquals("content://media/10", albums[0].coverUri.toString()) // cover = newest row
        assertEquals(2L, albums[1].bucketId)
        assertEquals(1, albums[1].count)
    }

    @Test
    fun `rows with a null bucket are skipped`() {
        val rows = listOf(row(1, null, null, "image/jpeg", 10L), row(2, 5, "Videos", "video/mp4", 20L))
        val albums = AlbumGrouper.group(rows) { id, mime -> uriFor(id, mime) }
        assertEquals(1, albums.size)
        assertEquals(5L, albums[0].bucketId)
        assertEquals(true, albums[0].coverIsVideo)
    }

    @Test
    fun `a blank bucket name falls back to a neutral title, never a filename`() {
        val rows = listOf(row(1, 7, "   ", "image/jpeg", 10L))
        val albums = AlbumGrouper.group(rows) { id, mime -> uriFor(id, mime) }
        assertEquals("Album", albums[0].title)
    }

    @Test
    fun `empty input yields no albums`() {
        assertEquals(emptyList<Any>(), AlbumGrouper.group(emptyList()) { id, _ -> uriFor(id, null) })
    }
}
