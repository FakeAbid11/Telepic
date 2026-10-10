package com.telepic.domain.media

import java.util.Locale

/**
 * Pure duration label for video cells (`h:mm:ss` past an hour, `m:ss` otherwise).
 *
 * A single implementation serves the timeline tiles and the cloud grid so a 90-minute clip reads
 * `1:30:00` everywhere instead of one surface rolling it up to `90:00`. Formatting follows
 * [Locale.getDefault] like the rest of the labels in this package. `null` means "nothing to show"
 * (unknown, zero or negative duration) — callers render no badge rather than a fabricated `0:00`.
 */
object DurationLabel {

    fun format(millis: Long?): String? {
        if (millis == null || millis <= 0L) return null
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }
}
