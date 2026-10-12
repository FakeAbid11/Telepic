package com.telepic.data.media

import com.telepic.domain.media.Album
import com.telepic.domain.media.PhotosItem
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

/**
 * Local albums (MediaStore buckets) for the Albums screen. Kept behind an interface so the UI and
 * ViewModels are testable with a fake and never talk to MediaStore (or any provider) directly.
 */
interface AlbumRepository {
    suspend fun albums(): List<Album>
    fun mediaInBucket(bucketId: Long): Flow<PagingData<PhotosItem>>

    /**
     * Invalidates the current paging source so the next load re-reads the bucket. Needed because a
     * `cachedIn` pager never re-queries on its own: archiving or trashing a photo from the Viewer
     * changed what the album should contain, and returning to an already-created album screen left
     * the item listed with a stale count. [LocalMediaRepository.refresh] is the same seam.
     */
    fun refresh()
}
