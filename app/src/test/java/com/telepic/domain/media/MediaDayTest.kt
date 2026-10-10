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
}
