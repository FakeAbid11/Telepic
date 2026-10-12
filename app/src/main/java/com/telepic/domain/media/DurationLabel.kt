package com.telepic.domain.media

/**
 * Pure duration label for video cells (`h:mm:ss` past an hour, `m:ss` otherwise).
 *
 * A single implementation serves the timeline tiles and the cloud grid so a 90-minute clip reads
 * `1:30:00` everywhere instead of one surface rolling it up to `90:00`. `null` means "nothing to
 * show" (unknown, zero or negative duration) — callers render no badge rather than a fabricated
 * `0:00`.
 *
 * The string is assembled by hand rather than with `String.format`: parsing the format string on
 * every call was measurable, because this runs once per video tile per recomposition. One
 * consequence, deliberately accepted: the digits are always ASCII. `String.format` could emit
 * locale-native digits for the handful of locales whose default numbering system is non-Latin;
 * every platform media UI renders ASCII here, and `DurationLabelTest` pins these exact strings.
 */
object DurationLabel {

    fun format(millis: Long?): String? {
        if (millis == null || millis <= 0L) return null
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "$hours:${twoDigits(minutes)}:${twoDigits(seconds)}"
        } else {
            "$minutes:${twoDigits(seconds)}"
        }
    }

    /** Zero-padded to two digits, matching the `%02d` the format string used to carry. */
    private fun twoDigits(value: Long): String = if (value < 10L) "0$value" else value.toString()
}
