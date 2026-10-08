package com.telepix.domain.media

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure calendar-day helpers used for chronological grouping.
 *
 * Uses [SimpleDateFormat] (not `java.time`) to stay compatible with minSdk 24 without core
 * library desugaring. Formatters are created per call to remain thread-safe across the paging
 * background dispatcher and the UI thread.
 */
object MediaDay {
    fun dayKey(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

    fun label(millis: Long): String =
        SimpleDateFormat("MMMM d, yyyy", Locale.US).format(Date(millis))
}
