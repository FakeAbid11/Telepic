package com.telepic.domain.media

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chronological grouping identity + relative day classification (Phase 8 date behavior). */
class MediaDayTest {

    private val zone = ZoneId.systemDefault()

    private fun millisOf(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `dayKey is a stable ISO date independent of the display label`() {
        val day = LocalDate.of(2026, 10, 8)
        val millis = millisOf(day)
        assertEquals("2026-10-08", MediaDay.dayKey(millis))
        // Two timestamps on the same local day share a key.
        val later = day.atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        assertEquals(MediaDay.dayKey(millis), MediaDay.dayKey(later))
    }

    @Test
    fun `epochDay matches the calendar day and orders the timeline`() {
        assertEquals(LocalDate.of(2026, 10, 8).toEpochDay(), MediaDay.epochDay(millisOf(LocalDate.of(2026, 10, 8))))
        assertTrue(MediaDay.epochDay(millisOf(LocalDate.of(2026, 10, 7))) < MediaDay.epochDay(millisOf(LocalDate.of(2026, 10, 8))))
    }

    @Test
    fun `relative kinds classify today, yesterday, this week and older`() {
        val today = LocalDate.now()
        val now = millisOf(today)
        assertEquals(MediaDay.DayKind.TODAY, MediaDay.relativeKind(millisOf(today), now))
        assertEquals(MediaDay.DayKind.YESTERDAY, MediaDay.relativeKind(millisOf(today.minusDays(1)), now))
        assertEquals(MediaDay.DayKind.THIS_WEEK, MediaDay.relativeKind(millisOf(today.minusDays(3)), now))
        assertEquals(MediaDay.DayKind.OLDER, MediaDay.relativeKind(millisOf(today.minusDays(30)), now))
    }

    @Test
    fun `a photo near midnight keeps its local day and does not shift to another day`() {
        // 23:59:59 local — the grouping must stay on the same local calendar day (§69).
        val day = LocalDate.of(2026, 10, 8)
        val late = day.atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
        assertEquals("2026-10-08", MediaDay.dayKey(late))
    }

    @Test
    fun `label is locale-formatted and older dates keep a full date`() {
        val label = MediaDay.label(millisOf(LocalDate.of(2023, 11, 14)))
        // Whatever the default locale produces, it is not the raw identity key and is stable.
        assertTrue(label.isNotEmpty())
        assertNotEquals("2023-11-14", label)
        assertEquals(label, MediaDay.label(millisOf(LocalDate.of(2023, 11, 14))))
    }

    @Test
    fun `monthDayOrFullDate compacts the current year but never drops the year of an older photo`() {
        val now = millisOf(LocalDate.of(2026, 10, 8))
        val thisYear = millisOf(LocalDate.of(2026, 3, 1))
        val older = millisOf(LocalDate.of(2023, 11, 14))

        // Same year as `now` → the compact month-and-day form, which by construction omits the year.
        assertEquals(MediaDay.monthDay(thisYear), MediaDay.monthDayOrFullDate(thisYear, now))
        // A different year → the full date, so the year is never silently lost.
        assertEquals(MediaDay.label(older), MediaDay.monthDayOrFullDate(older, now))
        // Asserted as a relationship, not a literal, so the test holds in any default locale.
        assertNotEquals(MediaDay.monthDay(older), MediaDay.monthDayOrFullDate(older, now))
    }

    @Test
    fun `the compact form is shorter than the full date so a per-item header can fit one line`() {
        val now = millisOf(LocalDate.of(2026, 10, 8))
        val thisYear = millisOf(LocalDate.of(2026, 9, 30))
        assertTrue(
            MediaDay.monthDayOrFullDate(thisYear, now).length < MediaDay.label(thisYear).length,
        )
    }
}
