package com.telepix.data.media

import android.net.Uri
import androidx.paging.LoadResult
import androidx.paging.PagingSource
import com.telepix.domain.media.LocalMedia
import com.telepix.domain.media.MediaType
import com.telepix.domain.media.PhotosItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the paging source's day-header behavior against a fake [MediaPageLoader]: headers on
 * day change, no duplicate header across a page boundary, reset on refresh, and error passthrough.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaPagingSourceTest {

    private val dayA = 1_700_000_000_000L
    private val dayB = dayA - 86_400_000L
    private val dayC = dayB - 86_400_000L

    private fun media(id: Long, dateMillis: Long): LocalMedia = LocalMedia(
        id = id,
        contentUri = Uri.parse("content://media/external/images/media/$id"),
        type = MediaType.PHOTO,
        mimeType = "image/jpeg",
        displayName = "f$id",
        dateMillis = dateMillis,
        durationMillis = null,
        width = 0,
        height = 0,
        sizeBytes = 0L,
        bucketId = null,
        bucketName = null,
        relativePath = null,
    )

    private class FakeLoader(private val all: List<LocalMedia>) : MediaPageLoader {
        override suspend fun load(offset: Int, limit: Int): List<LocalMedia> =
            all.drop(offset).take(limit)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun PagingSource<Int, PhotosItem>.refresh0(size: Int): LoadResult.Page<Int, PhotosItem> {
        val result = load(PagingSource.LoadParams.Refresh(0, size, false))
        assertTrue("expected page, got $result", result is LoadResult.Page)
        return result as LoadResult.Page<Int, PhotosItem>
    }

    @Test
    fun `inserts a header whenever the day changes`() = runTest {
        val source = MediaPagingSource(FakeLoader(listOf(media(3, dayA), media(2, dayA), media(1, dayB))))
        val page = source.refresh0(size = 10)

        val kinds = page.data.map { it is PhotosItem.Day }
        assertEquals(listOf(true, false, false, true, false), kinds)
        // End of library: fewer items than requested, so no next key.
        assertEquals(null, page.nextKey)
        assertEquals(null, page.prevKey)
    }

    @Test
    fun `does not duplicate a header across a page boundary`() = runTest {
        val all = listOf(media(5, dayA), media(4, dayA), media(3, dayA), media(2, dayB))
        val source = MediaPagingSource(FakeLoader(all))

        val first = source.refresh0(size = 2)
        assertEquals(listOf(true, false, false), first.data.map { it is PhotosItem.Day })
        assertEquals(2, first.nextKey)

        val appended = source.load(
            PagingSource.LoadParams.Append(2, 2, false),
        ) as LoadResult.Page<Int, PhotosItem>
        // Same day continues (no new header), then a new day gets one.
        assertEquals(listOf(false, true, false), appended.data.map { it is PhotosItem.Day })
    }

    @Test
    fun `refresh resets header state so the top day header reappears`() = runTest {
        val source = MediaPagingSource(FakeLoader(listOf(media(1, dayC))))
        assertEquals(true, source.refresh0(size = 5).data.first() is PhotosItem.Day)
        // A second refresh (invalidation) must still begin with a header for the only item.
        assertEquals(true, source.refresh0(size = 5).data.first() is PhotosItem.Day)
    }

    @Test
    fun `loader failure surfaces as an error result`() = runTest {
        val failing = object : MediaPageLoader {
            override suspend fun load(offset: Int, limit: Int): List<LocalMedia> =
                throw IllegalStateException("media store unavailable")
        }
        val result = MediaPagingSource(failing).load(
            PagingSource.LoadParams.Refresh(0, 10, false),
        )
        assertTrue(result is LoadResult.Error)
    }
}
