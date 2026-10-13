package com.telepic.data.media

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.telepic.domain.media.LocalMedia
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

    /**
     * Walks the whole loader in [ALL_PAGE_SIZE] steps until a short page says the end was reached.
     *
     * A short page is the only stop condition the loader offers, so [MAX_ALL_ITEMS] is a second,
     * defensive one: a loader that somehow kept returning full pages would otherwise spin here
     * forever, and an unbounded loop in a ViewModel coroutine is worse than a truncated selection.
     * The cap is far above any library this app is built for.
     */
    override suspend fun allMedia(): List<LocalMedia> {
        val all = ArrayList<LocalMedia>()
        var offset = 0
        while (all.size < MAX_ALL_ITEMS) {
            val page = loader.load(offset, ALL_PAGE_SIZE)
            if (page.isEmpty()) break
            all += page
            if (page.size < ALL_PAGE_SIZE) break
            offset += page.size
        }
        return all
    }

    companion object {
        private const val PAGE_SIZE = 60
        private const val PREFETCH_DISTANCE = 30
        private const val INITIAL_LOAD_SIZE = 90

        /** Page size for the whole-library walk — larger than the grid's, because nothing renders it. */
        private const val ALL_PAGE_SIZE = 500
        private const val MAX_ALL_ITEMS = 50_000
    }
}
