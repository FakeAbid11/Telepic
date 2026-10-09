package com.telepix.data.media

import android.database.MatrixCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for the MediaStore paging seek. The original code called
 * `moveToPosition(offset - 1)` for every page — including offset 0 — which asks the cursor for
 * position -1, returns false, and made the FIRST page read as an empty library on a real device
 * (never caught before because paging was previously exercised only through fake loaders).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CursorPagingTest {

    private fun cursorOf(vararg ids: Long): MatrixCursor =
        MatrixCursor(arrayOf("_id")).apply { ids.forEach { addRow(arrayOf(it)) } }

    /** Collects the ids a loader would emit for (offset, limit) using the shared seek semantics. */
    private fun page(cursor: MatrixCursor, offset: Int, limit: Int): List<Long> {
        val out = ArrayList<Long>()
        if (!cursor.seekToPageStart(offset)) return out
        var count = 0
        while (count < limit && cursor.moveToNext()) {
            out += cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
            count++
        }
        return out
    }

    @Test
    fun `first page at offset zero reads from the beginning and is not empty`() {
        val ids = page(cursorOf(10L, 20L, 30L), offset = 0, limit = 2)
        assertEquals(listOf(10L, 20L), ids)
    }

    @Test
    fun `empty cursor yields no items at offset zero`() {
        assertFalse(page(cursorOf(), offset = 0, limit = 5).any())
    }

    @Test
    fun `positive offset continues without duplicating or skipping`() {
        val cursor = cursorOf(10L, 20L, 30L, 40L)
        assertEquals(listOf(10L, 20L), page(cursor, offset = 0, limit = 2))
        // a fresh cursor per page, as the loader does
        assertEquals(listOf(30L, 40L), page(cursorOf(10L, 20L, 30L, 40L), offset = 2, limit = 2))
    }

    @Test
    fun `offset at or beyond the count yields an empty page and paging terminates cleanly`() {
        val all = cursorOf(10L, 20L, 30L)
        assertEquals(listOf(30L), page(cursorOf(10L, 20L, 30L), offset = 2, limit = 5))
        assertEquals(emptyList<Long>(), page(all, offset = 3, limit = 5))
        assertEquals(emptyList<Long>(), page(cursorOf(10L, 20L, 30L), offset = 99, limit = 5))
    }

    @Test
    fun `whole library is enumerable across pages with no duplicates or gaps`() {
        val source = (1L..7L).toList()
        val collected = ArrayList<Long>()
        var offset = 0
        while (true) {
            val chunk = page(cursorOf(*source.toLongArray()), offset, 3)
            if (chunk.isEmpty()) break
            collected += chunk
            offset += chunk.size
        }
        assertEquals(source, collected)
    }
}
