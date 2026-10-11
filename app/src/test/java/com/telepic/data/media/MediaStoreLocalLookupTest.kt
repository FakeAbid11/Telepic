package com.telepic.data.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The neighbour walk's contract, pinned without a ContentResolver: [walkToVisibleNeighbor]'s `next`
 * stands in for the provider, so the skip and give-up rules are asserted directly.
 */
class MediaStoreLocalLookupTest {

    /**
     * A descending id line, the shape the OLDER direction walks (`_ID < cursor`, nearest first). Given
     * a cursor it yields the largest id below it, or null at the end of the line.
     */
    private fun descendingLine(ids: Iterable<Long>): suspend (Long) -> Long? {
        val sorted = ids.toList()
        return { cursor -> sorted.filter { it < cursor }.maxOrNull() }
    }

    @Test
    fun `an unhidden neighbour costs exactly one step`() = runBlocking {
        var steps = 0
        val result = walkToVisibleNeighbor(startId = 10L, hidden = emptySet()) { cursor ->
            steps++
            if (cursor == 10L) 9L else null
        }
        assertEquals(9L, result)
        assertEquals(1, steps)
    }

    @Test
    fun `hidden neighbours are skipped until a visible one is found`() = runBlocking {
        val result = walkToVisibleNeighbor(
            startId = 10L,
            hidden = setOf(9L, 8L, 7L),
            next = descendingLine(9L downTo 6L),
        )
        assertEquals(6L, result)
    }

    @Test
    fun `the end of the collection reports no neighbour`() = runBlocking {
        val result = walkToVisibleNeighbor(
            startId = 10L,
            hidden = setOf(9L, 8L),
            next = descendingLine(9L downTo 8L),
        )
        assertNull(result)
    }

    @Test
    fun `a hidden run longer than the budget reports no neighbour, never a hidden id`() = runBlocking {
        // The honest fallback: running out of budget must not degrade into returning hidden media.
        val result = walkToVisibleNeighbor(
            startId = 201L,
            hidden = (1L..200L).toSet(),
            maxSteps = 4,
            next = descendingLine(200L downTo 1L),
        )
        assertNull(result)
    }

    @Test
    fun `a hidden set past the old SQL parameter bound is excluded exactly`() = runBlocking {
        // 1,000 hidden ids — twice the 500 that the SQL placeholder list used to be truncated to.
        // Truncation is exactly what let the Viewer land on filed-away media, so the walk must step
        // past all of them and reach the one visible item beyond.
        val result = walkToVisibleNeighbor(
            startId = 1_001L,
            hidden = (1L..1_000L).toSet(),
            maxSteps = 1_500,
            next = descendingLine(1_000L downTo 0L),
        )
        assertEquals(0L, result)
    }
}
