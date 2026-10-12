package com.telepic.data.media

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.cloud.db.TelepicDatabase
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The custom-album repository against a real (in-memory) Room database.
 *
 * The behaviours worth pinning are the ones where a plausible shortcut would produce a *wrong number*
 * rather than an error: "how many were added" has to exclude what was already there, the cover has to
 * follow the last addition without being blanked by an unrelated removal, and a blank name has to be
 * refused rather than stored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CustomAlbumRepositoryTest {

    private lateinit var db: TelepicDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TelepicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun media(id: Long) = LocalMedia(
        id = id,
        contentUri = Uri.parse("content://media/$id"),
        type = MediaType.PHOTO,
        mimeType = "image/jpeg",
        displayName = "p$id.jpg",
        dateMillis = 1_000L,
        durationMillis = null,
        width = 1,
        height = 1,
        sizeBytes = 1L,
        bucketId = null,
        bucketName = null,
        relativePath = null,
    )

    private class FakeLookup(private val items: Map<Long, LocalMedia>) : LocalMediaLookup {
        override suspend fun byId(id: Long): LocalMedia? = items[id]
        override suspend fun neighborId(id: Long, direction: NeighborDirection, bucketId: Long?): Long? = null
        override suspend fun byIdList(ids: Collection<Long>): List<LocalMedia> = ids.mapNotNull { items[it] }
    }

    private class FailingLookup : LocalMediaLookup {
        override suspend fun byId(id: Long): LocalMedia? = null
        override suspend fun neighborId(id: Long, direction: NeighborDirection, bucketId: Long?): Long? = null
        override suspend fun byIdList(ids: Collection<Long>): List<LocalMedia> =
            throw IllegalStateException("provider unavailable")
    }

    private fun repository(lookup: LocalMediaLookup = FakeLookup(emptyMap())) =
        DefaultCustomAlbumRepository(
            dao = db.customAlbumDao(),
            lookup = lookup,
            clock = { 1_000L },
        )

    @Test
    fun `creating an album trims the name and refuses a blank one`() = runBlocking {
        val repository = repository()

        val id = repository.create("  Trip  ")
        assertNotNull(id)
        assertEquals("Trip", repository.albums.first().single().name)

        // A blank name creates nothing at all — not an album called "".
        assertNull(repository.create("   "))
        assertEquals(1, repository.albums.first().size)
    }

    @Test
    fun `adding media reports only what was genuinely new`() = runBlocking {
        val repository = repository()
        val albumId = repository.create("Trip")!!

        assertEquals(3, repository.addMedia(albumId, listOf(1L, 2L, 3L)))
        // Two repeats plus one new item is worth exactly one.
        assertEquals(1, repository.addMedia(albumId, listOf(2L, 3L, 4L)))
        // Duplicates inside a single request count once.
        assertEquals(1, repository.addMedia(albumId, listOf(5L, 5L)))
        // Nothing new at all is worth zero, not "5".
        assertEquals(0, repository.addMedia(albumId, listOf(1L, 2L, 3L, 4L, 5L)))

        assertEquals(5, repository.albums.first().single().count)
    }

    @Test
    fun `the cover is the most recently added member`() = runBlocking {
        val repository = repository()
        val albumId = repository.create("Trip")!!

        repository.addMedia(albumId, listOf(1L, 2L))
        assertEquals(2L, db.customAlbumDao().observeAlbums().first().single().coverMediaId)

        // Adding to the album moves it to the front of the grid and re-points the cover.
        repository.addMedia(albumId, listOf(3L))
        assertEquals(3L, db.customAlbumDao().observeAlbums().first().single().coverMediaId)
    }

    @Test
    fun `removing a member clears the cover only when it was the cover`() = runBlocking {
        val repository = repository()
        val albumId = repository.create("Trip")!!
        repository.addMedia(albumId, listOf(1L, 2L))

        // 2 is the cover; removing a *different* member must leave it alone.
        repository.removeMedia(albumId, 1L)
        assertEquals(2L, db.customAlbumDao().observeAlbums().first().single().coverMediaId)

        // Removing the cover itself clears the hint rather than leaving a photo the album no longer
        // holds as its face.
        repository.removeMedia(albumId, 2L)
        assertNull(db.customAlbumDao().observeAlbums().first().single().coverMediaId)
        assertEquals(0, repository.albums.first().single().count)
    }

    @Test
    fun `renaming keeps the id and refuses a blank name`() = runBlocking {
        val repository = repository()
        val albumId = repository.create("Trip")!!

        repository.rename(albumId, "  Holiday ")
        val album = repository.albums.first().single()
        assertEquals(albumId, album.id)
        assertEquals("Holiday", album.name)

        repository.rename(albumId, "   ")
        assertEquals("Holiday", repository.albums.first().single().name)
    }

    @Test
    fun `deleting an album takes its membership with it`() = runBlocking {
        val repository = repository()
        val albumId = repository.create("Trip")!!
        repository.addMedia(albumId, listOf(1L, 2L))

        repository.delete(albumId)

        assertTrue(repository.albums.first().isEmpty())
        // The cascade, not a second delete statement: no membership may outlive its album.
        assertTrue(db.customAlbumDao().mediaIds(albumId).isEmpty())
    }

    @Test
    fun `covers resolve from the lookup`() = runBlocking {
        val repository = repository(FakeLookup(mapOf(2L to media(2L))))
        val albumId = repository.create("Trip")!!
        repository.addMedia(albumId, listOf(2L))

        assertEquals(Uri.parse("content://media/2"), repository.albums.first().single().coverUri)
    }

    @Test
    fun `a failed cover lookup degrades to no cover rather than losing the album`() = runBlocking {
        val repository = repository(FailingLookup())
        val albumId = repository.create("Trip")!!
        repository.addMedia(albumId, listOf(2L))

        // The album is still listed and still counts its members; only the cover is missing. A missing
        // cover is visibly missing, where dropping the album would look like data loss.
        val album = repository.albums.first().single()
        assertEquals("Trip", album.name)
        assertEquals(1, album.count)
        assertNull(album.coverUri)
    }
}
