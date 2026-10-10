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
}
