package com.telepix.domain.media

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Pure calendar-day helpers used for chronological grouping and human-friendly headers.
 *
 * Uses [java.time] (available from minSdk 26). Grouping and identity are resolved in the **device's
 * default zone** so a photo never jumps a day because of UTC formatting (§69); the stable day *key*
 * is an ISO date (identity), while the *label* is presentation and localized (§17/§68). Absolute
 * labels use [Locale.getDefault], so Telepix is not English-only.
 */
object MediaDay {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Stable, locale-independent day identity (ISO `yyyy-MM-dd`). Not for display. */
    fun dayKey(millis: Long): String = toLocalDate(millis).toString()

    /** Ordinal day number for ordering / rail positioning without reparsing. */
    fun epochDay(millis: Long): Long = toLocalDate(millis).toEpochDay()

    fun toLocalDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /**
     * The full absolute label ("November 14, 2023"), localized. Used for accessibility and as the
     * grid header for older dates. Relative words (Today/Yesterday) are added by the UI, which has
     * access to localized string resources.
     */
    fun label(millis: Long): String =
        toLocalDate(millis).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))

    /** Localized month-and-day for dates within the current year ("October 8"). */
    fun monthDay(millis: Long, locale: Locale = Locale.getDefault()): String =
        toLocalDate(millis).format(DateTimeFormatter.ofPattern("MMMM d", locale))

    /** Localized weekday for the recent-week range ("Monday"). */
    fun weekday(millis: Long, locale: Locale = Locale.getDefault()): String =
        toLocalDate(millis).format(DateTimeFormatter.ofPattern("EEEE", locale))

    /** Localized year ("2023") for far-past grouping. */
    fun year(millis: Long, locale: Locale = Locale.getDefault()): String =
        toLocalDate(millis).format(DateTimeFormatter.ofPattern("yyyy", locale))

    /**
     * Whether [millis] falls on the device's today / yesterday / within the last week, relative to
     * [nowMillis]. The UI maps these to localized "Today"/"Yesterday" strings.
     */
    fun relativeKind(millis: Long, nowMillis: Long): DayKind {
        val today = toLocalDate(nowMillis)
        val that = toLocalDate(millis)
        val diff = today.toEpochDay() - that.toEpochDay()
        return when {
            diff == 0L -> DayKind.TODAY
            diff == 1L -> DayKind.YESTERDAY
            diff in 2..6 -> DayKind THIS_WEEK
            else -> DayKind.OLDER
        }
    }

    enum class DayKind { TODAY, YESTERDAY, THIS_WEEK, OLDER }
}
