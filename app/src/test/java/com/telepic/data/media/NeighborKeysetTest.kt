package com.telepic.data.media

import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the neighbour-seek SQL and — more importantly — its bound-argument order.
 *
 * [neighborKeyset] is the pure half of the Viewer's neighbour lookup: the walk and the cursor reads
 * need a real `ContentResolver`, but the predicate does not. That makes this the one place the
 * keyset comparison can be verified against the timeline's own `(coalescedDate, _ID)` ordering. A
 * mis-ordered argument list would not throw — it would silently return no neighbour — so the order
 * is asserted explicitly.
 *
 * The `CAST(? AS INTEGER)` around each date operand is load-bearing too, and is pinned here so it
 * cannot be "simplified" away: the coalesced expression has no affinity, so a TEXT-bound parameter
 * would compare by storage class and match everything (or nothing). How that plays out against real
 * SQLite is asserted in [NeighborKeysetExecutionTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NeighborKeysetTest {

    private val mediaSelection = "MEDIA"

    private val coalesced =
        "COALESCE(NULLIF(${MediaStore.MediaColumns.DATE_TAKEN}, 0), " +
            "${MediaStore.MediaColumns.DATE_MODIFIED} * 1000)"

    private val id = MediaStore.MediaColumns._ID

    @Test
    fun `older seek walks the composite key downward with the anchor date twice then the id`() {
        val keyset = neighborKeyset(
            anchorDate = 1_700_000_000_000L,
            anchorId = 42L,
            direction = NeighborDirection.OLDER,
            bucketId = null,
            filter = TimelineMediaFilter.All,
            mediaTypeSelection = mediaSelection,
        )
        assertEquals(
            "$mediaSelection AND ($coalesced < CAST(? AS INTEGER) OR ($coalesced = CAST(? AS INTEGER) AND $id < ?))",
            keyset.selection,
        )
        // Anchor date twice (the comparison arm and the tie-break arm), then the anchor id.
        assertEquals(listOf("1700000000000", "1700000000000", "42"), keyset.args)
    }

    @Test
    fun `newer seek is the mirrored comparison`() {
        val keyset = neighborKeyset(
            anchorDate = 1L,
            anchorId = 9L,
            direction = NeighborDirection.NEWER,
            bucketId = null,
            filter = TimelineMediaFilter.All,
            mediaTypeSelection = mediaSelection,
        )
        assertEquals(
            "$mediaSelection AND ($coalesced > CAST(? AS INTEGER) OR ($coalesced = CAST(? AS INTEGER) AND $id > ?))",
            keyset.selection,
        )
        assertEquals(listOf("1", "1", "9"), keyset.args)
    }

    @Test
    fun `bucketId is appended after the id so the argument order stays positional`() {
        val keyset = neighborKeyset(
            anchorDate = 5L,
            anchorId = 6L,
            direction = NeighborDirection.OLDER,
            bucketId = 77L,
            filter = TimelineMediaFilter.All,
            mediaTypeSelection = mediaSelection,
        )
        assertEquals(
            "$mediaSelection AND ($coalesced < CAST(? AS INTEGER) OR ($coalesced = CAST(? AS INTEGER) AND $id < ?))" +
                " AND ${MediaStore.MediaColumns.BUCKET_ID} = ?",
            keyset.selection,
        )
        assertEquals(listOf("5", "5", "6", "77"), keyset.args)
    }

    @Test
    fun `the kind filter suffix carries no bound args so the argument list is unchanged`() {
        assertEquals("", TimelineMediaFilter.All.selectionSuffix())
        val unfiltered = neighborKeyset(1L, 2L, NeighborDirection.OLDER, null, TimelineMediaFilter.All, mediaSelection)
        assertFalse(unfiltered.selection.contains("MEDIA_TYPE !="))

        val hideVideos = TimelineMediaFilter(showVideos = false)
        val filtered = neighborKeyset(1L, 2L, NeighborDirection.OLDER, null, hideVideos, mediaSelection)
        assertEquals(
            "$mediaSelection AND ($coalesced < CAST(? AS INTEGER) OR ($coalesced = CAST(? AS INTEGER) AND $id < ?))" + hideVideos.selectionSuffix(),
            filtered.selection,
        )
        // The suffix is a constant literal, never a `?`, so the bound-arg list is untouched.
        assertEquals(listOf("1", "1", "2"), filtered.args)
    }
}
