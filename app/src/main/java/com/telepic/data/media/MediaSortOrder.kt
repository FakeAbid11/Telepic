package com.telepic.data.media

import android.provider.MediaStore
import com.telepic.domain.media.LocalMedia

/**
 * The single source of the timeline's newest-first ordering for every MediaStore read (global
 * timeline, bucket loader, album grouping, custom-album members).
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

    /**
     * The Kotlin mirror of [TIMELINE_DESC], for the one case SQLite cannot be left to do it alone.
     *
     * A custom album's members are read in chunks, because SQLite bounds how many `?` placeholders a
     * single `IN (...)` may carry; the chunk results then have to be merged back into one newest-first
     * sequence, and that merge needs a comparator. Reusing this one is what keeps an album's grid in
     * the same order as the timeline.
     *
     * `dateMillis` *is* the coalesced value [TIMELINE_DESC] orders by — [MediaDateResolver] resolves
     * DATE_TAKEN-first with a DATE_MODIFIED fallback, exactly the rule the sort expression encodes —
     * so the two cannot disagree about which photo is newer. If the SQL changes, change this with it.
     */
    val TIMELINE_DESC_COMPARATOR: Comparator<LocalMedia> =
        compareByDescending<LocalMedia> { it.dateMillis }.thenByDescending { it.id }
}

