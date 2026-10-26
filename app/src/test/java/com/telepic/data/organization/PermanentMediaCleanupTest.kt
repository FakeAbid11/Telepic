package com.telepic.data.organization

import androidx.room.Room
import com.telepic.data.cloud.db.TelepicDatabase
import com.telepic.data.media.db.CustomAlbumEntity
import com.telepic.data.media.db.CustomAlbumMediaEntity
import com.telepic.data.media.db.MediaLocationEntity
import com.telepic.data.media.db.MediaLocationScanEntity
import com.telepic.data.organization.db.MediaOrganizationEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Permanent deletion must remove every app-owned row keyed by the MediaStore id, not just the Trash
 * row. This pins the cross-table invariant in one real Room transaction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PermanentMediaCleanupTest {

    @Test
    fun `cleanup removes organization album location and scan rows`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, TelepicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val organization = db.mediaOrganizationDao()
            val albums = db.customAlbumDao()
            val locations = db.mediaLocationDao()
            val now = 123L

            val albumId = albums.insertAlbum(
                CustomAlbumEntity(
                    name = "Trip",
                    coverMediaId = 7L,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            albums.insertMembers(
                listOf(CustomAlbumMediaEntity(albumId = albumId, localMediaId = 7L, addedAt = now)),
            )
            organization.upsert(
                MediaOrganizationEntity(
                    localMediaId = "7",
                    isTrashed = true,
                    trashedAt = now,
                    updatedAt = now,
                ),
            )
            locations.upsert(
                MediaLocationEntity(
                    localMediaId = 7L,
                    latitude = 1.0,
                    longitude = 2.0,
                    contentUri = "content://media/7",
                    extractedAt = now,
                ),
            )
            locations.markScanned(listOf(MediaLocationScanEntity(7L, now)))

            RoomPermanentMediaCleanup(
                db = db,
                organizationDao = organization,
                customAlbumDao = albums,
                mediaLocationDao = locations,
                clock = { now },
            ).cleanup(listOf(7L))

            assertNull(organization.get("7"))
            assertTrue(albums.memberIds(albumId).isEmpty())
            assertNull(albums.observeAlbums().first().single().coverMediaId)
            assertTrue(locations.cachedIds().isEmpty())
            assertTrue(locations.scannedIds().isEmpty())
        } finally {
            db.close()
        }
    }
}
