package com.telepic.ui.screens.photos

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scrubber's pure geometry: section extraction adapts granularity to library depth, the finger's
 * track fraction maps onto a clamped grid index, and — the part that used to look wrong — labels,
 * the pill and the date bubble all share one mapping so they can never drift apart.
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

    // ---- Track geometry: one mapping shared by the labels, the pill and the bubble. ----

    @Test
    fun `trackFraction is the exact inverse of scrubIndex`() {
        val itemCount = 101
        for (index in 0 until itemCount) {
            assertEquals(index, scrubIndex(trackFraction(index, itemCount), itemCount))
        }
    }

    @Test
    fun `trackFraction clamps and survives a degenerate item count`() {
        assertEquals(0f, trackFraction(-3, 10), 1e-6f)
        assertEquals(1f, trackFraction(50, 10), 1e-6f)
        assertEquals(0f, trackFraction(5, 1), 1e-6f)
        assertEquals(0f, trackFraction(0, 0), 1e-6f)
    }

    @Test
    fun `the pill spans the travel and never runs past it`() {
        assertEquals(0f, thumbTopPx(0f, trackHeightPx = 1000f, thumbHeightPx = 200f), 1e-6f)
        assertEquals(400f, thumbTopPx(0.5f, 1000f, 200f), 1e-6f)
        assertEquals(800f, thumbTopPx(1f, 1000f, 200f), 1e-6f)
        // A fraction beyond the end is clamped rather than extrapolated off the track.
        assertEquals(800f, thumbTopPx(1.5f, 1000f, 200f), 1e-6f)
    }

    @Test
    fun `a pill taller than the track cannot move`() {
        assertEquals(0f, thumbTravelPx(trackHeightPx = 100f, thumbHeightPx = 250f), 1e-6f)
        assertEquals(0f, thumbTopPx(0.7f, 100f, 250f), 1e-6f)
    }

    @Test
    fun `labels align with the pill's centre rather than its top edge`() {
        assertEquals(100f, thumbCentrePx(0f, 1000f, 200f), 1e-6f)
        assertEquals(500f, thumbCentrePx(0.5f, 1000f, 200f), 1e-6f)
        // The pill's bottom reaches the track's bottom, so its centre sits half a pill above it.
        assertEquals(900f, thumbCentrePx(1f, 1000f, 200f), 1e-6f)
    }

    @Test
    fun `a drag moves the pill by exactly the finger's distance`() {
        // Travel is 800px, so a 400px drag is half the range — the pill stays under the finger.
        assertEquals(0.5f, dragFractionDelta(400f, trackHeightPx = 1000f, thumbHeightPx = 200f), 1e-6f)
        // Measuring against the whole track instead would give 0.4 and the pill would visibly lag.
        assertEquals(0.4f, dragFractionDelta(400f, 1000f, 0f), 1e-6f)
    }

    @Test
    fun `a drag on a degenerate track does not divide by zero`() {
        assertEquals(200f, dragFractionDelta(200f, trackHeightPx = 50f, thumbHeightPx = 300f), 1e-6f)
    }

    @Test
    fun `the section nearest the pill is the one emphasised`() {
        val sections = (0..9).map { anchor(it * 10, 20_000L - it) }
        assertEquals(0, activeSectionFor(sections, 0f, 100))
        assertEquals(5, activeSectionFor(sections, 0.5f, 100))
        assertEquals(9, activeSectionFor(sections, 1f, 100))
    }

    @Test
    fun `an empty section list is tolerated`() {
        assertEquals(0, activeSectionFor(emptyList(), 0.5f, 10))
    }

    // ---- The drag bubble's date, resolved by binary search over the day anchors. ----

    @Test
    fun `the bubble names the last day at or before the scrubbed item`() {
        val anchors = listOf(anchor(0, 100L), anchor(10, 200L), anchor(25, 300L))
        assertEquals(100L, bubbleAnchorFor(anchors, 0)?.epochDay)
        assertEquals(100L, bubbleAnchorFor(anchors, 9)?.epochDay)
        assertEquals(200L, bubbleAnchorFor(anchors, 10)?.epochDay)
        assertEquals(200L, bubbleAnchorFor(anchors, 24)?.epochDay)
        assertEquals(300L, bubbleAnchorFor(anchors, 25)?.epochDay)
        assertEquals(300L, bubbleAnchorFor(anchors, 999)?.epochDay)
    }

    @Test
    fun `an index above the first anchor falls back to the newest day`() {
        val anchors = listOf(anchor(5, 100L), anchor(9, 200L))
        assertEquals(100L, bubbleAnchorFor(anchors, 0)?.epochDay)
    }

    @Test
    fun `no anchors means no bubble`() {
        assertNull(bubbleAnchorFor(emptyList(), 3))
    }
}
