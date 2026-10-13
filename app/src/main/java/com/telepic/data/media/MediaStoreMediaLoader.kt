package com.telepic.data.media

import android.content.Context
import android.os.CancellationSignal
import android.provider.MediaStore
import com.telepic.domain.media.LocalMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Reads the local photo/video library through MediaStore as a single, DB-sorted, paged stream.
 *
 * Uses the [MediaStore.Files] collection so images and videos share one chronological ordering
 * (newest-first) resolved by the provider, instead of loading the whole library into memory and
 * sorting on the device. Only the requested projection columns are read, and only one page is
 * materialized per call. Runs on [Dispatchers.IO] and is cancellation-aware.
 *
 * [hiddenIdsProvider] is consulted per load and its ids are excluded **in the query itself**
 * (`_ID NOT IN (...)`), so archived/trashed media never occupies a timeline slot. Keeping the filter
 * database-side means paging offsets stay correct without loading anything extra.
 *
 * [typeFilterProvider] is the same idea for the user's hide-videos / hide-GIFs preferences: the
 * exclusion is part of the `WHERE` clause, so a hidden kind never occupies a page slot either. It
 * defaults to [TimelineMediaFilter.All], which emits no SQL at all — callers that must never filter
 * (the backup discovery loader) simply do not pass one.
 *
 * [searchQueryProvider] adds the user's search term as a `LIKE` over the file name and the folder
 * name. It is a **view of the same query**, not a second query path: the term narrows the same
 * projection, the same ordering and the same exclusions, so a search can never surface an archived
 * item or a hidden kind that the unfiltered timeline would have kept out. A blank term emits no SQL.
 */
class MediaStoreMediaLoader(
    context: Context,
    private val hiddenIdsProvider: suspend () -> Set<Long> = { emptySet() },
    private val typeFilterProvider: suspend () -> TimelineMediaFilter = { TimelineMediaFilter.All },
    private val searchQueryProvider: suspend () -> String? = { null },
) : MediaPageLoader {

    private val contentResolver = context.applicationContext.contentResolver

    private val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.Video.Media.DURATION,
    )

    // Restrict to images + videos only (excludes audio/documents in the Files table).
    private val selection =
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"

    private val sortOrder = MediaSortOrder.TIMELINE_DESC

    override suspend fun load(offset: Int, limit: Int): List<LocalMedia> =
        withContext(Dispatchers.IO) {
            val scope = this
            val hidden = hiddenIdsProvider()
            val (where, args) = buildSelection(hidden, typeFilterProvider(), searchQueryProvider())
            val collection = MediaStore.Files.getContentUri("external")
            val cancellation = CancellationSignal()
            val results = ArrayList<LocalMedia>(limit)

            contentResolver.query(
                collection,
                projection,
                where,
                args,
                sortOrder,
                cancellation,
            )?.use { cursor ->
                // The cursor starts before the first row, so the newest-first first page (offset 0)
                // is read directly with moveToNext(); only a positive offset seeks (see seekToPageStart).
                if (!cursor.seekToPageStart(offset)) return@use
                var count = 0
                while (count < limit && cursor.moveToNext()) {
                    scope.ensureActive()
                    val row = CursorMediaRow(cursor)
                    val id = row.long(MediaStore.MediaColumns._ID) ?: continue
                    val mimeType = row.string(MediaStore.MediaColumns.MIME_TYPE)
                    val uri = MediaStoreMediaMapper.contentUriFor(id, mimeType)
                    results += MediaStoreMediaMapper.map(row, uri)
                    count++
                }
            }
            results
        }

    /**
     * Builds the `WHERE` clause in a fixed order — kind filter, then search, then hidden ids — and
     * returns the bound arguments in that same order. Getting the order wrong silently binds a
     * filename pattern to an `_ID` slot, so the two are built together rather than apart.
     *
     * The search term is matched against the file name *and* the folder name, case-insensitively
     * (`LIKE` is case-insensitive for ASCII in SQLite), with the caller's `%`/`_` escaped so a term
     * containing them searches for those characters instead of turning into a wildcard.
     */
    private fun buildSelection(
        hidden: Set<Long>,
        filter: TimelineMediaFilter,
        search: String?,
    ): Pair<String, Array<String>?> {
        val args = ArrayList<String>()
        val where = StringBuilder(selection).append(filter.selectionSuffix())

        val term = search?.trim().orEmpty()
        if (term.isNotEmpty()) {
            val pattern = "%${escapeLike(term)}%"
            where.append(" AND (").append(MediaStore.MediaColumns.DISPLAY_NAME).append(" LIKE ? ESCAPE '\\'")
                .append(" OR ").append(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME).append(" LIKE ? ESCAPE '\\')")
            args += pattern
            args += pattern
        }

        if (hidden.isNotEmpty()) {
            // Bounded to a safe argument count so an extreme archive/trash size never exceeds
            // SQLite's parameter limit. Beyond the bound the surplus ids are simply not excluded this
            // pass (an accepted, documented edge case for ordinary libraries).
            val bounded = hidden.take(MAX_EXCLUDED_IDS)
            if (bounded.isNotEmpty()) {
                where.append(" AND ").append(MediaStore.MediaColumns._ID).append(" NOT IN (")
                    .append(bounded.joinToString(",") { "?" }).append(")")
                args += bounded.map { it.toString() }
            }
        }

        return where.toString() to args.toTypedArray().takeIf { it.isNotEmpty() }
    }

    /** Escapes the `LIKE` metacharacters so a literal `%` or `_` in a filename still matches itself. */
    private fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        const val MAX_EXCLUDED_IDS = 500
    }
}
