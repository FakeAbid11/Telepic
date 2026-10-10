package com.telepic.data.media

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.telepic.domain.media.PhotosItem
import kotlinx.coroutines.flow.Flow

/**
 * [LocalMediaRepository] built on Paging 3 over a [MediaPageLoader].
 *
 * [refresh] invalidates the current source so the next load re-reads MediaStore from the newest
 * item; a new source instance is produced by the factory, giving clean per-generation state.
 */
class LocalMediaRepositoryImpl(
    private val loader: MediaPageLoader,
) : LocalMediaRepository {

    private var currentSource: MediaPagingSource? = null

    override val media: Flow<PagingData<PhotosItem>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            prefetchDistance = PREFETCH_DISTANCE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = {
            MediaPagingSource(loader).also { currentSource = it }
        },
    ).flow

    override fun refresh() {
        currentSource?.invalidate()
    }

    companion object {
        private const val PAGE_SIZE = 60
        private const val PREFETCH_DISTANCE = 30
        private const val INITIAL_LOAD_SIZE = 90
    }
}
