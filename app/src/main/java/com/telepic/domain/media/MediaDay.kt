package com.telepic.domain.media

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure calendar-day helpers used for chronological grouping and human-friendly headers.
 *
 * Uses [java.time] (available from minSdk 26). Grouping and identity are resolved in the **device's
 * default zone** so a photo never jumps a day because of UTC formatting (§69); the stable day *key*
 * is an ISO date (identity), while the *label* is presentation and localized (§17/§68). Absolute
 * labels use [Locale.getDefault], so Telepic is not English-only.
 */
object MediaDay {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /**
     * Formatters cached per locale.
     *
     * Building one is not free: `ofLocalizedDate` re-resolves the localized pattern and constructs a
     * new formatter, and `ofPattern` compiles the pattern. These used to be built on *every* call —
     * once per grid tile through the accessibility label, and once per day header inside the paging
     * load, so paging a multi-year library rebuilt the same few formatters hundreds of times.
     * [DateTimeFormatter] is immutable and thread-safe, so one per locale is enough. Keying by locale
     * (rather than caching a single instance) keeps a runtime locale change correct.
     */
    private val absoluteDates = ConcurrentHashMap<Locale, DateTimeFormatter>()
    private val monthDays = ConcurrentHashMap<Locale, DateTimeFormatter>()
    private val weekdays = ConcurrentHashMap<Locale, DateTimeFormatter>()
    private val years = ConcurrentHashMap<Locale, DateTimeFormatter>()

    /** The FORMAT-category locale, which is the one `ofLocalizedDate` binds to. */
    private fun formatLocale(): Locale = Locale.getDefault(Locale.Category.FORMAT)

    private fun absoluteDate(locale: Locale): DateTimeFormatter =
        absoluteDates.getOrPut(locale) {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
        }

    private fun monthDayFormatter(locale: Locale): DateTimeFormatter =
        monthDays.getOrPut(locale) { DateTimeFormatter.ofPattern("MMMM d", locale) }

    private fun weekdayFormatter(locale: Locale): DateTimeFormatter =
        weekdays.getOrPut(locale) { DateTimeFormatter.ofPattern("EEEE", locale) }

    private fun yearFormatter(locale: Locale): DateTimeFormatter =
        years.getOrPut(locale) { DateTimeFormatter.ofPattern("yyyy", locale) }

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
        toLocalDate(millis).format(absoluteDate(formatLocale()))

    /** Localized month-and-day for dates within the current year ("October 8"). */
    fun monthDay(millis: Long, locale: Locale = Locale.getDefault()): String =
        toLocalDate(millis).format(monthDayFormatter(locale))

    /**
     * The label for a *single item* header — the Viewer's top bar — as opposed to [label], which
     * names a whole day group. Month-and-day while [millis] shares a year with [nowMillis], the full
     * date otherwise: a compact per-item header must never silently drop the year of an older photo.
     *
     * [nowMillis] is a parameter rather than a clock read so the choice is deterministic in tests.
     */
    fun monthDayOrFullDate(millis: Long, nowMillis: Long): String =
        if (toLocalDate(millis).year == toLocalDate(nowMillis).year) monthDay(millis) else label(millis)

    /** Localized weekday for the recent-week range ("Monday"). */
    fun weekday(millis: Long, locale: Locale = Locale.getDefault()): String =
        toLocalDate(millis).format(weekdayFormatter(locale))

    /** Localized year ("2023") for far-past grouping. */
    fun year(millis: Long, locale: Locale = Locale.getDefault()): String =
        toLocalDate(millis).format(yearFormatter(locale))

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
            diff in 2..6 -> DayKind.THIS_WEEK
            else -> DayKind.OLDER
        }
    }

    enum class DayKind { TODAY, YESTERDAY, THIS_WEEK, OLDER }
}
