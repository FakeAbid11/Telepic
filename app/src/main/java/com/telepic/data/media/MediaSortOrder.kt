package com.telepic.data.media

import android.provider.MediaStore

/**
 * The single source of the timeline's newest-first ordering for every MediaStore read (global
 * timeline, bucket loader, album grouping).
 *
 * The display date ([MediaDateResolver]) falls back to DATE_MODIFIED*1000 when DATE_TAKEN is
 * 0/NULL — a screen recording or side-loaded video still reads as its modified day. Ordering only
 * by DATE_TAKEN would park those rows at the bottom of the cursor while they are labeled with a
 * recent day, producing duplicated, non-monotonic day headers. Ordering by the same coalesced
 * value the UI shows keeps headers sorted, once each, and monotonic.
 */
object MediaSortOrder {

    val TIMELINE_DESC =
        "COALESCE(NULLIF(${MediaStore.MediaColumns.DATE_TAKEN}, 0), " +
            "${MediaStore.MediaColumns.DATE_MODIFIED} * 1000) DESC, " +
            "${MediaStore.MediaColumns._ID} DESC"
}
