package com.telepic.data.media

import android.net.Uri
import androidx.paging.PagingSource
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import com.telepic.domain.media.PhotosItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private typealias Page = PagingSource.LoadResult.Page<Int, PhotosItem>

/**
 * Verifies the paging source's day-header behavior against a fake [MediaPageLoader]: headers on
 * day change, no duplicate header across a page boundary, reset at the top, and error passthrough.
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

    private suspend fun PagingSource<Int, PhotosItem>.refreshTop(size: Int): Page {
        val result = load(PagingSource.LoadParams.Refresh(0, size, false))
        assertTrue("expected page, got $result", result is Page)
        return result as Page
    }

    @Test
    fun `inserts a header whenever the day changes`() = runTest {
        val source = MediaPagingSource(
            FakeLoader(listOf(media(3, dayA), media(2, dayA), media(1, dayB))),
        )
        val page = source.refreshTop(size = 10)

        assertEquals(listOf(true, false, false, true, false), page.data.map { it is PhotosItem.Day })
        assertEquals(null, page.nextKey)
        assertEquals(null, page.prevKey)
    }

    @Test
    fun `does not duplicate a header across a page boundary`() = runTest {
        val all = listOf(media(5, dayA), media(4, dayA), media(3, dayA), media(2, dayB))
        val source = MediaPagingSource(FakeLoader(all))

        val first = source.refreshTop(size = 2)
        assertEquals(listOf(true, false, false), first.data.map { it is PhotosItem.Day })
        assertEquals(2, first.nextKey)

        val appended = source.load(PagingSource.LoadParams.Append(2, 2, false)) as Page
        assertEquals(listOf(false, true, false), appended.data.map { it is PhotosItem.Day })
    }

    @Test
    fun `refresh at the top resets header state`() = runTest {
        val source = MediaPagingSource(FakeLoader(listOf(media(1, dayC))))
        assertTrue(source.refreshTop(size = 5).data.first() is PhotosItem.Day)
        assertTrue(source.refreshTop(size = 5).data.first() is PhotosItem.Day)
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
        assertTrue(result is PagingSource.LoadResult.Error)
    }
}
