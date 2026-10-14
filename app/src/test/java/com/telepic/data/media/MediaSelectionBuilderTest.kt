package com.telepic.data.media

import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the one part of the MediaStore boundary that is pure logic: the `WHERE` clause and its bound
 * arguments. [MediaStoreMediaLoader] itself needs a `ContentResolver` and a real provider, but the
 * clause it builds decides what the timeline shows — and a wrong clause fails *silently*, by returning
 * the wrong rows rather than by throwing.
 *
 * The specific failure modes pinned here are the ones that actually bit this project: an argument
 * bound to the wrong `?`, a search term whose `%`/`_` turned into a wildcard, and a cap that dropped
 * hidden ids past 500 so archived/trashed media reappeared in the timeline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaSelectionBuilderTest {

    private val mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE
    private val displayName = MediaStore.MediaColumns.DISPLAY_NAME
    private val bucketName = MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
    private val idColumn = MediaStore.MediaColumns._ID

    @Test
    fun `restricts the query to images and videos`() {
        val (where, args) = MediaSelectionBuilder.build(TimelineMediaFilter.All, null, emptySet())
        val expected =
            "$mediaType IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        assertEquals(expected, where)
        assertNull("an unfiltered, unsearched, unhidden query binds nothing", args)
    }

    @Test
    fun `hiding a kind adds its exclusion to the clause`() {
        val (unfiltered, _) = MediaSelectionBuilder.build(TimelineMediaFilter.All, null, emptySet())
        val (hidden, _) = MediaSelectionBuilder.build(TimelineMediaFilter(showVideos = false), null, emptySet())
        assertEquals(
            "$unfiltered AND $mediaType != ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}",
            hidden,
        )
    }

    @Test
    fun `a blank or whitespace search term emits no SQL and no arguments`() {
        for (term in listOf(null, "", "   ")) {
            val (where, args) = MediaSelectionBuilder.build(TimelineMediaFilter.All, term, emptySet())
            assertEquals(MediaSelectionBuilder.baseSelection(), where)
            assertNull("a blank term must not bind a pattern", args)
        }
    }

    @Test
    fun `search binds two escaped patterns, display name then bucket name`() {
        val (where, args) = MediaSelectionBuilder.build(TimelineMediaFilter.All, "100%_off", emptySet())
        assertEquals(listOf("%100\\%\\_off%", "%100\\%\\_off%"), args?.toList())
        assertEquals(
            "$displayName LIKE ? ESCAPE '\\' OR $bucketName LIKE ? ESCAPE '\\'",
            where.substringAfter(" AND (").substringBefore(")"),
        )
    }

    @Test
    fun `hidden ids are inlined as literals and never bound`() {
        val (where, args) = MediaSelectionBuilder.build(TimelineMediaFilter.All, null, setOf(7L, 9L))
        assertTrue(where, where.endsWith(" AND $idColumn NOT IN (7,9)"))
        assertNull("hidden ids must not consume bound parameters", args)
    }

    @Test
    fun `more hidden ids than SQLite allows bound parameters still work`() {
        // The regression this guards: these used to be bound as parameters behind a `take(500)` cap,
        // so past 500 archived/trashed items the surplus was silently dropped and reappeared in the
        // timeline. SQLite caps bound parameters (~999) but not literals in an `IN (...)` list.
        val (where, args) = MediaSelectionBuilder.build(TimelineMediaFilter.All, null, (1L..2_000L).toSet())
        assertNull(args)
        assertTrue(where, where.contains(" AND $idColumn NOT IN (1,2,3,"))
        assertTrue(where, where.endsWith("2000)"))
    }

    @Test
    fun `clause order is kind filter, then search, then hidden ids`() {
        // The order is load-bearing: the arguments are appended in the same pass, so a clause that
        // moved would bind a filename pattern to an `_ID` slot.
        val (where, _) = MediaSelectionBuilder.build(TimelineMediaFilter(showVideos = false), "a", setOf(5L))
        val kindAt = where.indexOf("$mediaType !=")
        val searchAt = where.indexOf(displayName)
        val hiddenAt = where.indexOf("NOT IN (")
        assertTrue("the kind filter must come first", kindAt in 0 until searchAt)
        assertTrue("the search must precede the hidden ids", searchAt < hiddenAt)
    }

    @Test
    fun `escapeLike escapes the backslash before the wildcards`() {
        assertEquals("a\\\\b\\%c\\_d", MediaSelectionBuilder.escapeLike("a\\b%c_d"))
    }
}
