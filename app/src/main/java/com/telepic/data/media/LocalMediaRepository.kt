package com.telepic.data.media

import androidx.paging.PagingData
import com.telepic.domain.media.PhotosItem
import kotlinx.coroutines.flow.Flow

/**
 * The repository the Photos layer consumes. MediaStore access stays behind this interface so
 * later phases (albums, backup source selection) and tests can consume local media cleanly, and
 * so tests can replace the implementation with a fake.
 */
interface LocalMediaRepository {
    /** Paged, newest-first stream of day headers + media cells. */
    val media: Flow<PagingData<PhotosItem>>

    /** Re-read the library (e.g., after a MediaStore change or explicit refresh). */
    fun refresh()
}
