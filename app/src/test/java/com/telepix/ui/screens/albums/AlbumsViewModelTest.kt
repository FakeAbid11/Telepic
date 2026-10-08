package com.telepix.ui.screens.albums

import android.net.Uri
import androidx.paging.PagingData
import com.telepix.data.media.AlbumRepository
import com.telepix.domain.media.Album
import com.telepix.domain.media.PhotosItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Album loading states: ready, genuinely empty, and error distinct from empty. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumsViewModelTest {

    private fun album(id: Long) = Album(
        bucketId = id, title = "A$id", coverUri = Uri.parse("content://c/$id"),
        coverIsVideo = false, count = 3, latestMillis = id,
    )

    private class FakeAlbums(private val result: suspend () -> List<Album>) : AlbumRepository {
        override suspend fun albums(): List<Album> = result()
        override fun mediaInBucket(bucketId: Long): Flow<PagingData<PhotosItem>> = flowOf(PagingData.empty())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads albums into the ready state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = AlbumsViewModel(FakeAlbums { listOf(album(1), album(2)) })
        runCurrent()
        val status = vm.status.value
        assertTrue(status is AlbumsStatus.Ready)
        assertEquals(2, (status as AlbumsStatus.Ready).albums.size)
    }

    @Test
    fun `empty library is the empty state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = AlbumsViewModel(FakeAlbums { emptyList() })
        runCurrent()
        assertEquals(AlbumsStatus.Empty, vm.status.value)
    }

    @Test
    fun `a provider failure is the error state, not empty`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = AlbumsViewModel(FakeAlbums { throw IllegalStateException("boom") })
        runCurrent()
        assertEquals(AlbumsStatus.Error, vm.status.value)
    }
}
