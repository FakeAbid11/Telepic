package com.telepix.data.media

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.telepix.domain.media.MediaDay
import com.telepix.domain.media.PhotosItem
import kotlinx.coroutines.CancellationException

/**
 * Turns the paged, newest-first media stream into a flat [PhotosItem] stream with day headers.
 *
 * Forward (append) paging only — a newest-first timeline never scrolls upward past the top. A
 * header is inserted whenever the day changes; [lastDayKey] is carried across appends of the same
 * generation (so a day split across a page boundary does not get a duplicate header) and reset on
 * a refresh/initial load. A fresh source instance is created on invalidation, so state is clean.
 */
class MediaPagingSource(
    private val loader: MediaPageLoader,
) : PagingSource<Int, PhotosItem>() {

    private var lastDayKey: String? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, PhotosItem> {
        val offset = params.key ?: 0
        val loadSize = params.loadSize.coerceAtLeast(1)

        if (params.loadType is androidx.paging.LoadType.Refresh) {
            lastDayKey = null
        }

        return try {
            val media = loader.load(offset, loadSize)
            val items = ArrayList<PhotosItem>(media.size + 1)

            for (item in media) {
                val dayKey = MediaDay.dayKey(item.dateMillis)
                if (dayKey != lastDayKey) {
                    items += PhotosItem.Day(dayKey, MediaDay.label(item.dateMillis))
                    lastDayKey = dayKey
                }
                items += PhotosItem.Media(item)
            }

            LoadData(
                data = items,
                prevKey = null,
                nextKey = if (media.size < loadSize) null else offset + media.size,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            LoadResult.Error(throwable)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, PhotosItem>): Int =
        state.anchorPosition?.let(state::closestPageToPosition)?.prevKey ?: 0
}
