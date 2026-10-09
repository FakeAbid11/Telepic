package com.telepix.data.media

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepix.data.cloud.db.TelepixDatabase
import com.telepix.data.media.db.MediaLocationDao
import com.telepix.domain.media.GeoLocation
import com.telepix.domain.media.LocalMedia
import com.telepix.domain.media.MediaType
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
 * The map cache extracts GPS only for geotagged items (never fabricates a coordinate), skips already
 * cached items on rescan, and exposes the cached locations for rendering with the correct id + URI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MapLocationRepositoryTest {

    private lateinit var db: TelepixDatabase
    private lateinit var dao: MediaLocationDao

    private val items = listOf(
        media(1, "content://m/1"),
        media(2, "content://m/2"),
        media(3, "content://m/3"),
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
        db = Room.inMemoryDatabaseBuilder(context, TelepixDatabase::class.java).allowMainThreadQueries().build()
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
    fun `rescan skips already-cached geotagged items`() = runBlocking {
        val extractor = FakeExtractor()
        val repository = DefaultMapLocationRepository(dao, FakeLoader(items), extractor, pageSize = 10, maxPages = 5)
        repository.rescan()
        val afterFirst = extractor.calls.toList()

        repository.rescan()
        // ids 1 and 3 are cached, so only the never-cached (coordinate-less) id 2 is re-examined.
        val added = extractor.calls.drop(afterFirst.size)
        assertEquals(listOf("content://m/2"), added)
        assertEquals(setOf(1L, 3L), repository.locations.first().map { it.mediaId }.toSet())
    }

    private fun media(id: Long, uri: String) = LocalMedia(
        id = id, contentUri = Uri.parse(uri), type = MediaType.PHOTO, mimeType = "image/jpeg",
        displayName = "f$id", dateMillis = 1L, durationMillis = null, width = 1, height = 1, sizeBytes = 1L,
        bucketId = null, bucketName = null, relativePath = null,
    )
}
