package com.telepic.ui.screens.photos

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scrubber's pure geometry: section extraction adapts granularity to library depth, and the
 * finger's track fraction maps onto a clamped grid index.
 */
class DateScrubberTest {

    private fun anchor(index: Int, epochDay: Long) = DateAnchor(index, epochDay, "L$epochDay")

    @Test
    fun `month sections keep only the first anchor of each month`() {
        val jan31 = LocalDate.of(2024, 1, 31).toEpochDay()
        val jan2 = LocalDate.of(2024, 1, 2).toEpochDay()
        val dec31 = LocalDate.of(2023, 12, 31).toEpochDay()
        val anchors = listOf(anchor(0, jan31), anchor(5, jan2), anchor(9, dec31))
        assertEquals(listOf(jan31, dec31), monthSections(anchors).map { it.epochDay })
    }

    @Test
    fun `year sections keep only the first anchor of each year`() {
        val jan = LocalDate.of(2024, 1, 15).toEpochDay()
        val dec = LocalDate.of(2023, 12, 31).toEpochDay()
        val nov = LocalDate.of(2022, 11, 5).toEpochDay()
        val anchors = listOf(anchor(0, jan), anchor(4, dec), anchor(8, nov))
        assertEquals(listOf(jan, dec, nov), yearSections(anchors).map { it.epochDay })
    }

    @Test
    fun `a shallow library uses month sections`() {
        val anchors = (0..5).map { anchor(it, LocalDate.of(2024, 6, 30 - it).toEpochDay()) }
        val chosen = chooseSections(anchors, maxSections = 12)!!
        assertEquals(SectionGranularity.Month, chosen.second)
    }

    @Test
    fun `a deep library falls back to year sections`() {
        // 24 months of day anchors → month sections (24) exceed any small window, years (2) win.
        val anchors = (0 until 24).map { month ->
            anchor(month, LocalDate.of(2024 - month / 12, 12 - month % 12, 1).toEpochDay())
        }
        val chosen = chooseSections(anchors, maxSections = 12)!!
        assertEquals(SectionGranularity.Year, chosen.second)
    }

    @Test
    fun `empty anchors choose nothing`() {
        assertNull(chooseSections(emptyList(), maxSections = 12))
    }

    @Test
    fun `scrub fraction maps onto a clamped grid index`() {
        assertEquals(0, scrubIndex(-1f, 10))
        assertEquals(9, scrubIndex(2f, 10))
        assertEquals(5, scrubIndex(0.5f, 11))
        assertEquals(0, scrubIndex(0.5f, 0))
        assertEquals(0, scrubIndex(0f, 1))
    }
}
