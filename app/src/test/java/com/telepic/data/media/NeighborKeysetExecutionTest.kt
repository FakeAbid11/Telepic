package com.telepic.data.media

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs the neighbour-seek predicate against real SQLite, through the same TEXT binding the app uses.
 *
 * `NeighborKeysetTest` pins the SQL *text*; this pins its *meaning*, which is where the Viewer's
 * swipe bug lived. `ContentResolver.query` (and `rawQuery` here) binds `selectionArgs` as TEXT, and
 * the coalesced date expression has no affinity — so without the `CAST(? AS INTEGER)` the `<` arm
 * matched every row (returning the newest item) and the `>` arm matched none (locking the swipe).
 * A test that only inspected the string could not see that; one that executes it can.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NeighborKeysetExecutionTest {

    private lateinit var db: SQLiteDatabase

    private val mediaSelection =
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        // The exact column names the predicate embeds, so the generated SQL runs unmodified.
        db.execSQL(
            "CREATE TABLE media (" +
                "${MediaStore.MediaColumns._ID} INTEGER PRIMARY KEY, " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} INTEGER NOT NULL, " +
                "${MediaStore.MediaColumns.MIME_TYPE} TEXT, " +
                "${MediaStore.MediaColumns.DATE_TAKEN} INTEGER, " +
                "${MediaStore.MediaColumns.DATE_MODIFIED} INTEGER, " +
                "${MediaStore.MediaColumns.BUCKET_ID} INTEGER)",
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insert(id: Long, dateTaken: Long?, dateModified: Long, bucketId: Long = 1L) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns._ID, id)
            put(MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken)
            put(MediaStore.MediaColumns.DATE_MODIFIED, dateModified)
            put(MediaStore.MediaColumns.BUCKET_ID, bucketId)
        }
        db.insertOrThrow("media", null, values)
    }

    /** The neighbour the app would open, exactly as [MediaStoreLocalLookup] asks for it. */
    private fun neighbor(
        anchorId: Long,
        anchorDate: Long,
        direction: NeighborDirection,
        bucketId: Long? = null,
    ): Long? {
        val keyset = neighborKeyset(
            anchorDate = anchorDate,
            anchorId = anchorId,
            direction = direction,
            bucketId = bucketId,
            filter = TimelineMediaFilter.All,
            mediaTypeSelection = mediaSelection,
        )
        // The order rides along from MediaSortOrder in production, exactly as queryNeighbor applies it.
        val order = if (direction == NeighborDirection.OLDER) {
            MediaSortOrder.TIMELINE_DESC
        } else {
            MediaSortOrder.TIMELINE_ASC
        }
        return db.rawQuery(
            "SELECT ${MediaStore.MediaColumns._ID} FROM media WHERE ${keyset.selection} ORDER BY $order",
            keyset.args.toTypedArray(),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    @Test
    fun `each direction lands on the immediately adjacent item`() {
        insert(id = 1, dateTaken = 100, dateModified = 0)
        insert(id = 2, dateTaken = 200, dateModified = 0)
        insert(id = 3, dateTaken = 300, dateModified = 0)
        insert(id = 4, dateTaken = 400, dateModified = 0)

        assertEquals(1L, neighbor(anchorId = 2, anchorDate = 200, direction = NeighborDirection.OLDER))
        assertEquals(3L, neighbor(anchorId = 2, anchorDate = 200, direction = NeighborDirection.NEWER))
    }

    @Test
    fun `the ends of the collection report no neighbour`() {
        insert(id = 1, dateTaken = 100, dateModified = 0)
        insert(id = 2, dateTaken = 200, dateModified = 0)

        assertNull(neighbor(anchorId = 1, anchorDate = 100, direction = NeighborDirection.OLDER))
        assertNull(neighbor(anchorId = 2, anchorDate = 200, direction = NeighborDirection.NEWER))
    }

    @Test
    fun `a zero DATE_TAKEN seeks by the DATE_MODIFIED fallback`() {
        // Coalesced order: id 1 (100000) < id 3 (200000) < id 2 (300000), so the neighbour by date
        // is id 3 even though id 2 is numerically closer.
        insert(id = 1, dateTaken = 0, dateModified = 100)
        insert(id = 2, dateTaken = 0, dateModified = 300)
        insert(id = 3, dateTaken = 0, dateModified = 200)

        assertEquals(3L, neighbor(anchorId = 1, anchorDate = 100_000, direction = NeighborDirection.NEWER))
        assertEquals(3L, neighbor(anchorId = 2, anchorDate = 300_000, direction = NeighborDirection.OLDER))
    }

    @Test
    fun `equal dates break the tie on the id`() {
        insert(id = 9, dateTaken = 400, dateModified = 0)
        insert(id = 10, dateTaken = 500, dateModified = 0)
        insert(id = 11, dateTaken = 500, dateModified = 0)

        assertEquals(11L, neighbor(anchorId = 10, anchorDate = 500, direction = NeighborDirection.NEWER))
        assertEquals(9L, neighbor(anchorId = 10, anchorDate = 500, direction = NeighborDirection.OLDER))
        assertNull(neighbor(anchorId = 11, anchorDate = 500, direction = NeighborDirection.NEWER))
    }

    @Test
    fun `a bucketId confines the seek to that bucket`() {
        insert(id = 1, dateTaken = 100, dateModified = 0, bucketId = 1)
        insert(id = 2, dateTaken = 200, dateModified = 0, bucketId = 1)
        insert(id = 4, dateTaken = 150, dateModified = 0, bucketId = 2)

        // id 4 is chronologically between 1 and 2 but lives in another bucket, so it is skipped.
        assertEquals(2L, neighbor(anchorId = 1, anchorDate = 100, direction = NeighborDirection.NEWER, bucketId = 1))
        assertEquals(1L, neighbor(anchorId = 2, anchorDate = 200, direction = NeighborDirection.OLDER, bucketId = 1))
        assertNull(neighbor(anchorId = 4, anchorDate = 150, direction = NeighborDirection.NEWER, bucketId = 2))
    }

    private companion object {
        const val DB_NAME = "neighbor_keyset_execution.db"
    }
}
