package com.telepic.ui.screens.albums

import android.net.Uri
import androidx.paging.PagingData
import com.telepic.data.media.AlbumRepository
import com.telepic.domain.media.Album
import com.telepic.domain.media.PhotosItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Album loading states: ready, genuinely empty, and error distinct from empty. Runs on Robolectric so
 * `viewModelScope`'s Main dispatcher is the (synchronizable) test looper, exactly as on a device.
 * The ViewModel no longer self-loads — [refresh] (driven by the screen once access exists) is the
 * only entry point, so a denied user never triggers a doomed query.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlbumsViewModelTest {

    private fun album(id: Long) = Album(
        bucketId = id, title = "A$id", coverUri = Uri.parse("content://c/$id"),
        coverIsVideo = false, count = 3, latestMillis = id,
    )

    private class FakeAlbums(private val result: suspend () -> List<Album>) : AlbumRepository {
        override suspend fun albums(): List<Album> = result()
        override fun mediaInBucket(bucketId: Long): Flow<PagingData<PhotosItem>> = flowOf(PagingData.empty())
    }

    /** Mirrors the screen: the caller triggers the load, then the looper drains to a terminal state. */
    private fun AlbumsViewModel.settle(): AlbumsStatus {
        refresh()
        repeat(5) { shadowOf(android.os.Looper.getMainLooper()).idle() }
        return status.value
    }

    @Test
    fun `construction alone never queries - the caller gates on access`() {
        var queries = 0
        val vm = AlbumsViewModel(FakeAlbums { queries++; listOf(album(1)) })
        repeat(5) { shadowOf(android.os.Looper.getMainLooper()).idle() }
        assertEquals(0, queries)
        assertEquals(AlbumsStatus.Loading, vm.status.value) // honest initial state, no doomed flash
    }

    @Test
    fun `loads albums into the ready state`() {
        val vm = AlbumsViewModel(FakeAlbums { listOf(album(1), album(2)) })
        val status = vm.settle()
        assertTrue("expected Ready but was $status", status is AlbumsStatus.Ready)
        assertEquals(2, (status as AlbumsStatus.Ready).albums.size)
    }

    @Test
    fun `empty library is the empty state`() {
        val vm = AlbumsViewModel(FakeAlbums { emptyList() })
        assertEquals(AlbumsStatus.Empty, vm.settle())
    }

    @Test
    fun `a provider failure is the error state, not empty`() {
        val vm = AlbumsViewModel(FakeAlbums { throw IllegalStateException("boom") })
        assertEquals(AlbumsStatus.Error, vm.settle())
    }
}
