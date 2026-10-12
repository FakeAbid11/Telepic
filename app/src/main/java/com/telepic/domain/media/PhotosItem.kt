package com.telepic.domain.media

import androidx.compose.runtime.Immutable

/**
 * A flat, paged item for the Photos grid: either a day header or a media cell.
 *
 * Headers occupy a full grid row (via `GridItemSpan.maxLineSpan`); media occupy one cell.
 * [key] is stable across recomposition so lazy items don't rebind unnecessarily.
 *
 * [Immutable] is load-bearing, not decoration: [Media] wraps a [LocalMedia], which the compiler
 * cannot prove stable, so without this the whole item type is unstable and every cell that receives
 * one is invalidated whenever its parent recomposes. Both implementations are `val`-only and hold
 * immutable values, so the contract holds.
 */
@Immutable
sealed interface PhotosItem {
    val key: String

    @Immutable
    data class Day(val dayKey: String, val epochDay: Long, val label: String) : PhotosItem {
        override val key: String = "day_$dayKey"
    }

    @Immutable
    data class Media(val media: LocalMedia) : PhotosItem {
        override val key: String = "media_${media.id}"
    }
}
