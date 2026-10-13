package com.telepic.data.media

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.cloud.db.TelepicDatabase
import com.telepic.data.media.db.MediaLocationDao
import com.telepic.domain.media.GeoLocation
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The map cache extracts GPS only for geotagged items (never fabricates a coordinate) and exposes
 * the cached locations for rendering with the correct id + URI.
 *
 * It also pins the scan's *memory*: every examined item is recorded, coordinate or not, so a later
 * sweep never re-reads the same file. That memory is what lets the sweep get past a long
 * coordinate-less prefix to the geotagged photos behind it — the failure that left the map stuck on
 * its loading state for a library whose newest files were screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MapLocationRepositoryTest {

    private lateinit var db: TelepicDatabase
    private lateinit var dao: MediaLocationDao

    private val items = listOf(
        media(1L, "content://m/1"),
        media(2L, "content://m/2"),
        media(3L, "content://m/3"),
    )

    private class FakeLoader(private val items: List<LocalMedia>) : MediaPageLoader {
        var loadCalls = 0
        override suspend fun load(offset: Int, limit: Int): List<LocalMedia> {
            loadCalls++
            return items.drop(offset).take(limit)
        }
    }

    // Only ids 1 and 3 have GPS; id 2 is deliberately coordinate-less.
    private class FakeExtractor : LocationExtractor {
        val calls = mutableListOf<String>()
        override suspend fun extract(uri: Uri): GeoLocation? {
            calls += uri.toString()
            return when (uri.toString()) {
                "content://m/1" -> GeoLocation(48.85, 2.35)
                "content://m/3" -> GeoLocation(40.71, -74.0)
                else -> null
            }
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TelepicDatabase::class.java).allowMainThreadQueries().build()
        dao = db.mediaLocationDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `only geotagged items are cached and never a fabricated location`() = runBlocking {
        val repository = DefaultMapLocationRepository(dao, FakeLoader(items), FakeExtractor(), pageSize = 10, maxPages = 5)
        repository.rescan()
        val cached = repository.locations.first()
        assertEquals(setOf(1L, 3L), cached.map { it.mediaId }.toSet())
        assertTrue(cached.all { it.location.isValid })
        assertEquals("content://m/1", cached.first { it.mediaId == 1L }.contentUri)
    }

    @Test
    fun `a coordinate-less item is examined once and never re-read`() = runBlocking {
        val extractor = FakeExtractor()
        val repository = DefaultMapLocationRepository(dao, FakeLoader(items), extractor, pageSize = 10, maxPages = 5)
        repository.rescan()
        assertEquals(listOf("content://m/1", "content://m/2", "content://m/3"), extractor.calls)

        repository.rescan()
        // Nothing is re-read — including id 2, which carries no coordinate and so is absent from
        // `media_location`. Re-reading it on every sweep is exactly what used to spend the whole page
        // budget on the same prefix and leave the map stuck on its loading state, because the
        // geotagged files further back were then never reached at all.
        assertEquals(listOf("content://m/1", "content://m/2", "content://m/3"), extractor.calls)
        assertEquals(setOf(1L, 3L), repository.locations.first().map { it.mediaId }.toSet())
    }

    @Test
    fun `the sweep reaches a geotagged photo beyond the old page ceiling`() = runBlocking {
        // 5,000 coordinate-less files ahead of the only geotagged one. The sweep used to stop after
        // 40 pages (4,800 items) *and* spend that budget re-reading the same already-examined
        // prefix, so this photo was unreachable: the map could never show it however often it was
        // opened. Uses the production defaults on purpose — the ceiling was a default, so only a
        // default can prove it is gone.
        val library = (1L..5_000L).map { media(it, "content://m/$it") }
        val extractor = object : LocationExtractor {
            override suspend fun extract(uri: Uri): GeoLocation? =
                if (uri.toString() == "content://m/5000") GeoLocation(48.85, 2.35) else null
        }
        val repository = DefaultMapLocationRepository(dao, FakeLoader(library), extractor)
        repository.rescan()
        assertEquals(setOf(5_000L), repository.locations.first().map { it.mediaId }.toSet())
    }

    @Test
    fun `rescan drops markers for media no longer in the library`() = runBlocking {
        val repository = DefaultMapLocationRepository(dao, FakeLoader(items), FakeExtractor(), pageSize = 10, maxPages = 5)
        repository.rescan()
        assertEquals(setOf(1L, 3L), repository.locations.first().map { it.mediaId }.toSet())

        // id 3 is removed from the device; the next complete rescan reconciles the cache.
        val afterDelete = listOf(items[0], items[1]) // ids 1 and 2 only
        val reopened = DefaultMapLocationRepository(dao, FakeLoader(afterDelete), FakeExtractor(), pageSize = 10, maxPages = 5)
        reopened.rescan()
        assertEquals(setOf(1L), reopened.locations.first().map { it.mediaId }.toSet())
    }

    @Test
    fun `a scan that cannot reach the library end does not prune still-existing markers`() = runBlocking {
        val repository = DefaultMapLocationRepository(dao, FakeLoader(items), FakeExtractor(), pageSize = 10, maxPages = 5)
        repository.rescan() // caches 1 and 3

        // Only one page of size 2 is read, so id 3 is never seen this pass — but because the scan did
        // not reach the end, the reconcile-prune must not run and id 3 stays.
        val truncated = DefaultMapLocationRepository(dao, FakeLoader(items), FakeExtractor(), pageSize = 2, maxPages = 1)
        truncated.rescan()
        assertEquals(setOf(1L, 3L), truncated.locations.first().map { it.mediaId }.toSet())
    }

    private fun media(id: Long, uri: String) = LocalMedia(
        id = id, contentUri = Uri.parse(uri), type = MediaType.PHOTO, mimeType = "image/jpeg",
        displayName = "f$id", dateMillis = 1L, durationMillis = null, width = 1, height = 1, sizeBytes = 1L,
        bucketId = null, bucketName = null, relativePath = null,
    )
}
