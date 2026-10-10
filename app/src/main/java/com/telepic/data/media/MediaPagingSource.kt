package com.telepic.data.media

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.telepic.domain.media.MediaDay
import com.telepic.domain.media.PhotosItem
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

        // Offset 0 is the top of the newest-first stream (initial or post-invalidate refresh):
        // reset header state there. Appends carry a day boundary forward instead.
        if (offset == 0) {
            lastDayKey = null
        }

        return try {
            val media = loader.load(offset, loadSize)
            val items = ArrayList<PhotosItem>(media.size + 1)

            for (item in media) {
                val dayKey = MediaDay.dayKey(item.dateMillis)
                if (dayKey != lastDayKey) {
                    items += PhotosItem.Day(
                        dayKey = dayKey,
                        epochDay = MediaDay.epochDay(item.dateMillis),
                        label = MediaDay.label(item.dateMillis),
                    )
                    lastDayKey = dayKey
                }
                items += PhotosItem.Media(item)
            }

            LoadResult.Page(
                data = items,
                prevKey = null,
                nextKey = if (media.isEmpty() || media.size < loadSize) null else offset + media.size,
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
