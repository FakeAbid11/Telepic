package com.telepic.ui.screens.photos

import com.telepic.domain.media.PhotosItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The date-rail anchor scan has to stay incremental while a library pages (only never-examined cells
 * are visited) and fall back to a full rebuild when the loaded list shrinks or a refresh remaps the
 * prefix — otherwise the rail silently points at the wrong rows.
 */
class DateAnchorScanTest {

    private fun day(epochDay: Long) = PhotosItem.Day(dayKey = "d$epochDay", epochDay = epochDay, label = "Label$epochDay")

    /** A stand-in pager: [cells] holds the day headers (null = media/absent cell), [visited] records reads. */
    private class FakePager(val cells: List<PhotosItem.Day?>) {
        val visited = mutableListOf<Int>()

        /** The head cell's stable key — what the composable uses to spot a new Paging generation. */
        val firstKey: String? get() = cells.firstOrNull()?.key

        val dayAt: (Int) -> PhotosItem.Day? = { index ->
            visited += index
            cells.getOrNull(index)
        }
    }

    private val layout = listOf(day(10), null, null, day(9), null)

    @Test
    fun `a first pass anchors every loaded day`() {
        val pager = FakePager(layout)

        val anchors = updateDateAnchors(emptyList(), firstUncheckedIndex = 0, newCount = 5, dayAt = pager.dayAt)

        assertEquals(listOf(0, 3), anchors.map { it.index })
        assertEquals(listOf(10L, 9L), anchors.map { it.epochDay })
        assertEquals(listOf("Label10", "Label9"), anchors.map { it.absoluteLabel })
        assertEquals(listOf(0, 1, 2, 3, 4), pager.visited)
    }

    @Test
    fun `appending a page reads only the anchor probe and the new tail`() {
        val pager = FakePager(layout)
        val first = updateDateAnchors(emptyList(), 0, 3, pager.dayAt)
        pager.visited.clear()

        val grown = updateDateAnchors(first, firstUncheckedIndex = 3, newCount = 5, dayAt = pager.dayAt)

        assertEquals(listOf(0, 3), grown.map { it.index })
        // Index 1 and 2 (already-seen media) are never re-read.
        assertEquals(listOf(0, 3, 4), pager.visited)
    }

    @Test
    fun `a day header landing exactly on the page boundary is picked up`() {
        val pager = FakePager(listOf(day(20), null, day(19)))
        val before = updateDateAnchors(emptyList(), 0, 2, pager.dayAt)
        pager.visited.clear()

        val after = updateDateAnchors(before, firstUncheckedIndex = 2, newCount = 3, dayAt = pager.dayAt)

        assertEquals(listOf(0, 2), after.map { it.index })
        assertEquals(listOf(0, 2), pager.visited)
    }

    @Test
    fun `a shrinking list rebuilds from the start`() {
        val pager = FakePager(layout)
        val before = updateDateAnchors(emptyList(), 0, 5, pager.dayAt)
        pager.visited.clear()

        val after = updateDateAnchors(before, firstUncheckedIndex = 0, newCount = 2, dayAt = pager.dayAt)

        assertEquals(listOf(0), after.map { it.index })
        assertEquals(listOf(0, 1), pager.visited)
    }

    @Test
    fun `a refresh that remaps the prefix rebuilds from the start`() {
        val pager = FakePager(layout)
        val before = updateDateAnchors(emptyList(), 0, 5, pager.dayAt)
        // The refreshed generation holds the same count but moved the older day up one row.
        val refreshed = FakePager(listOf(day(10), null, day(9), null))

        val after = updateDateAnchors(before, firstUncheckedIndex = 0, newCount = 4, dayAt = refreshed.dayAt)

        assertEquals(listOf(0, 2), after.map { it.index })
        assertEquals(listOf(0, 1, 2, 3), refreshed.visited)
    }

    @Test
    fun `an anchor whose recorded day no longer matches is never reused`() {
        val before = listOf(DateAnchor(index = 0, epochDay = 10, absoluteLabel = "Label10"))
        val shifted = FakePager(listOf(day(99), null))

        val after = updateDateAnchors(before, firstUncheckedIndex = 1, newCount = 2, dayAt = shifted.dayAt)

        assertEquals(listOf(10L), before.map { it.epochDay })
        assertEquals(listOf(99L), after.map { it.epochDay })
    }

    @Test
    fun `the holder recomputes only when the count or the generation changes`() {
        val pager = FakePager(layout)
        val holder = AnchorScanHolder()

        val first = holder.update(3, pager.firstKey, pager.dayAt)
        pager.visited.clear()

        // Same count, same head key: nothing is re-read at all.
        assertSame(first, holder.update(3, pager.firstKey, pager.dayAt))
        assertEquals(emptyList<Int>(), pager.visited)

        val grown = holder.update(5, pager.firstKey, pager.dayAt)
        assertEquals(listOf(0, 3), grown.map { it.index })
        assertEquals(listOf(0, 3, 4), pager.visited)

        pager.visited.clear()
        val shrunk = holder.update(2, pager.firstKey, pager.dayAt)
        assertEquals(listOf(0), shrunk.map { it.index })
        assertEquals(listOf(0, 1), pager.visited)
    }

    @Test
    fun `a same-count refresh with a new head key rebuilds from zero`() {
        val holder = AnchorScanHolder()
        val before = FakePager(layout)
        holder.update(5, before.firstKey, before.dayAt)

        // A refresh that swaps in a different first day but the very same loaded count.
        val after = FakePager(listOf(day(77), null, day(9), null, null))
        after.visited.clear()

        val rebuilt = holder.update(5, after.firstKey, after.dayAt)

        assertEquals(listOf(0, 2), rebuilt.map { it.index })
        assertEquals(listOf(77L, 9L), rebuilt.map { it.epochDay })
        // The rebuild really restarted at index 0 rather than trusting the old generation.
        assertEquals(listOf(0, 1, 2, 3, 4), after.visited)
    }
}
