package com.telepic.data.media

import androidx.paging.PagingData
import com.telepic.domain.media.LocalMedia
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

    /**
     * Every item the timeline currently shows, in timeline order, read in pages.
     *
     * This exists for "select all": a paged grid only ever holds the pages the user has scrolled
     * through, so selecting everything *loaded* would silently be a subset of the view — and a bulk
     * backup of a subset that reports itself as the whole library is exactly the kind of claim this
     * app does not make. It reads through the same loader, so it honours the same exclusions
     * (archived/trashed ids, the hide-videos/hide-GIFs preference) as the grid it stands in for.
     *
     * Deliberately not cached: a library the user just changed must not be selected from a stale list.
     * Callers run it off the main thread and expect it to take as long as the library is large.
     */
    suspend fun allMedia(): List<LocalMedia>
}
