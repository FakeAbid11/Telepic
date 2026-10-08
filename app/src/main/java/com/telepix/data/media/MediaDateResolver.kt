package com.telepix.data.media

/**
 * Resolves a single chronological timestamp (epoch millis) for a media item.
 *
 * Prefers `DATE_TAKEN` (already milliseconds) and falls back to `DATE_MODIFIED` (seconds),
 * never the current date, so the timeline stays truthful even for files without capture time.
 */
object MediaDateResolver {

    fun resolveMillis(dateTakenMillis: Long?, dateModifiedSeconds: Long?): Long {
        dateTakenMillis?.takeIf { it > 0L }?.let { return it }
        dateModifiedSeconds?.takeIf { it > 0L }?.let { return it * 1000L }
        return 0L
    }
}
