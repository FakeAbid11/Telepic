package com.telepic.data.media

import android.provider.MediaStore

/**
 * Builds the `WHERE` clause a timeline MediaStore query runs with, in a fixed order — kind filter,
 * then search, then hidden ids — returning the bound arguments in that same order.
 *
 * Extracted from [MediaStoreMediaLoader] so the one part of that boundary which is pure logic can be
 * exercised on the JVM: the loader needs a `ContentResolver`, but the clause it builds needs nothing
 * — and a wrong clause is what silently breaks the timeline (an argument bound to the wrong `?`, an
 * unescaped `%` turning a filename into a wildcard, or a hidden id that stops excluding).
 */
internal object MediaSelectionBuilder {

    /**
     * Restricts the query to images and videos. `MediaStore.Files` also holds audio and documents, so
     * without this a timeline page would be filled with rows the grid cannot render.
     */
    fun baseSelection(): String =
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"

    /**
     * The search term is matched against the file name *and* the folder name, case-insensitively
     * (`LIKE` is case-insensitive for ASCII in SQLite), with the caller's `%`/`_` escaped so a term
     * containing them searches for those characters instead of turning into a wildcard.
     */
    fun build(
        filter: TimelineMediaFilter,
        search: String?,
        hidden: Set<Long>,
    ): Pair<String, Array<String>?> {
        val args = ArrayList<String>()
        val where = StringBuilder(baseSelection()).append(filter.selectionSuffix())

        val term = search?.trim().orEmpty()
        if (term.isNotEmpty()) {
            val pattern = "%${escapeLike(term)}%"
            where.append(" AND (").append(MediaStore.MediaColumns.DISPLAY_NAME).append(" LIKE ? ESCAPE '\\'")
                .append(" OR ").append(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME).append(" LIKE ? ESCAPE '\\')")
            args += pattern
            args += pattern
        }

        if (hidden.isNotEmpty()) {
            // The hidden ids are interpolated as *integer literals*, not bound parameters. SQLite caps
            // the number of bound parameters (~999 on the oldest supported SQLite) but places no such
            // limit on literals in an `IN (...)` list, and every value here is a `Long` read from
            // MediaStore — `toString()` yields only digits and a sign, so nothing can be injected. The
            // previous `take(MAX_EXCLUDED_IDS)` bound the *parameters* to 500 and silently dropped the
            // surplus, so once a library had more than 500 archived/trashed items they reappeared in
            // the timeline. Interpolating removes that ceiling instead of hiding it.
            where.append(" AND ").append(MediaStore.MediaColumns._ID).append(" NOT IN (")
                .append(hidden.joinToString(",") { it.toString() }).append(")")
        }

        return where.toString() to args.toTypedArray().takeIf { it.isNotEmpty() }
    }

    /** Escapes the `LIKE` metacharacters so a literal `%` or `_` in a filename still matches itself. */
    fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
