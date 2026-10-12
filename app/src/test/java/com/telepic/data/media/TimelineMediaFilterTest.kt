package com.telepic.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timeline media filter is applied as SQL, so these tests pin the *exact* clause it produces.
 *
 * Asserting on the rendered string rather than on the data class's fields is deliberate: the failure
 * this guards against is not "the wrong boolean was stored" but "the query stopped excluding the right
 * thing", and only the SQL shows that. The literals `media_type` / `mime_type` / `2` are MediaStore's
 * own compile-time constants, so they are readable here without a device.
 */
class TimelineMediaFilterTest {

    @Test
    fun `the default filter adds no SQL at all`() {
        val filter = TimelineMediaFilter.All
        assertTrue(filter.isUnfiltered)
        assertEquals("", filter.selectionSuffix())
    }

    @Test
    fun `hiding videos excludes only the video media type`() {
        assertEquals(
            " AND media_type != 2",
            TimelineMediaFilter(showVideos = false).selectionSuffix(),
        )
    }

    @Test
    fun `hiding gifs excludes only the gif mime type`() {
        // GIFs are images as far as MediaStore is concerned, so the MIME string is the only identity.
        assertEquals(
            " AND mime_type != 'image/gif'",
            TimelineMediaFilter(showGifs = false).selectionSuffix(),
        )
    }

    @Test
    fun `hiding both joins the clauses into one AND chain`() {
        assertEquals(
            " AND media_type != 2 AND mime_type != 'image/gif'",
            TimelineMediaFilter(showVideos = false, showGifs = false).selectionSuffix(),
        )
    }

    @Test
    fun `each clause is independently switchable`() {
        assertTrue(TimelineMediaFilter().selectionSuffix().isEmpty())
        // Hiding videos must not also drop GIFs, and vice versa — they are separate preferences.
        assertFalse(TimelineMediaFilter(showVideos = false).selectionSuffix().contains("mime_type"))
        assertFalse(TimelineMediaFilter(showGifs = false).selectionSuffix().contains("media_type"))
    }

    @Test
    fun `the gif clause matches the classifier's own constant`() {
        // A drift between these two would hide GIFs by one rule and classify them by another.
        assertTrue(
            TimelineMediaFilter(showGifs = false).selectionSuffix().contains(MediaClassifier.GIF_MIME_TYPE),
        )
    }
}
