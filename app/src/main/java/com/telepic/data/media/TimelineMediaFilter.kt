package com.telepic.data.media

import android.provider.MediaStore

/**
 * Which media kinds the timeline-facing MediaStore queries may return.
 *
 * The user can hide videos and/or animated GIFs from the library views. The filter is applied **in
 * the query** (`AND MEDIA_TYPE != …` / `AND MIME_TYPE != …`) rather than to the page after it is read,
 * for the same reason the archived-id exclusion is: a page filtered after the fact returns fewer rows
 * than Paging 3 asked for, which it reads as "end of the library" and truncates the timeline at.
 *
 * Three deliberate boundaries:
 *
 * - **Backup eligibility is untouched.** What gets backed up is governed by the backup preference,
 *   never by what the grid happens to display, so hiding videos must not silently drop them from a
 *   backup the user asked for. `AppContainer.backupDiscoveryLoader` is constructed without this filter.
 * - **Explicit id resolution is untouched.** `LocalMediaLookup.byId` / `byIdList` still resolve a
 *   hidden item, so a Favorites or Trash collection keeps showing what it actually holds. Only the
 *   *neighbour walk* honours the filter, so a swipe never lands on something the grid refuses to show
 *   — the two surfaces cannot disagree about what is adjacent.
 * - **Nothing is inferred.** An unfiltered filter produces no SQL at all, so the common case costs
 *   exactly what it did before.
 */
data class TimelineMediaFilter(
    val showVideos: Boolean = true,
    val showGifs: Boolean = true,
) {
    /** True when every supported kind is shown, so no extra SQL is needed. */
    val isUnfiltered: Boolean get() = showVideos && showGifs

    /**
     * The `AND …` fragment excluding the hidden kinds, or `""` when nothing is hidden.
     *
     * GIFs are matched on MIME type ([MediaClassifier.GIF_MIME_TYPE]) because MediaStore classifies
     * them as ordinary images. The literal is a compile-time constant, never user input, so it is safe
     * to inline; a bound parameter would also force the caller to interleave it with the hidden-id
     * arguments, which is exactly the kind of positional coupling that breaks silently.
     */
    fun selectionSuffix(): String {
        val clauses = ArrayList<String>(2)
        if (!showVideos) {
            clauses += "${MediaStore.Files.FileColumns.MEDIA_TYPE} != " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
        }
        if (!showGifs) {
            clauses += "${MediaStore.MediaColumns.MIME_TYPE} != '${MediaClassifier.GIF_MIME_TYPE}'"
        }
        return if (clauses.isEmpty()) "" else clauses.joinToString(" AND ", prefix = " AND ")
    }

    companion object {
        /** Everything shown — the state before any preference has been read. */
        val All = TimelineMediaFilter()
    }
}
