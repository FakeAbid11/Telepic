package com.telepic.domain.media

/**
 * A flat, paged item for the Photos grid: either a day header or a media cell.
 *
 * Headers occupy a full grid row (via `GridItemSpan.maxLineSpan`); media occupy one cell.
 * [key] is stable across recomposition so lazy items don't rebind unnecessarily.
 */
sealed interface PhotosItem {
    val key: String

    data class Day(val dayKey: String, val epochDay: Long, val label: String) : PhotosItem {
        override val key: String = "day_$dayKey"
    }

    data class Media(val media: LocalMedia) : PhotosItem {
        override val key: String = "media_${media.id}"
    }
}
