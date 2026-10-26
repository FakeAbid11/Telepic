package com.telepic.data.organization

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.telepic.data.media.db.CustomAlbumDao
import com.telepic.data.media.db.MediaLocationDao
import com.telepic.data.organization.db.MediaOrganizationDao

/**
 * Removes every app-owned row that references a media id after MediaStore confirmed the file is gone.
 *
 * Permanent deletion is a cross-table invariant, not a single DELETE: Favorites/Archive/Trash state,
 * user-album membership and its cover hint, the GPS cache and the map's scan memory all use the same
 * MediaStore id. Leaving any of them behind produces ghost album counts, stale map pins, or a Trash
 * row for a file that no longer exists. [RoomPermanentMediaCleanup] owns that invariant in one
 * transaction so a partial cleanup cannot survive a crash.
 */
interface PermanentMediaCleanup {
    /** Remove all app-owned references to [ids]. Ids must already be confirmed deleted. */
    suspend fun cleanup(ids: Collection<Long>)
}

/**
 * [PermanentMediaCleanup] over the app database.
 *
 * Chunked queries keep every `IN (...)` under SQLite's bound-parameter limit while the surrounding
 * transaction keeps the whole cleanup atomic. The cover hint is cleared before membership is removed
 * so the album cannot briefly point at a member that just disappeared.
 */
class RoomPermanentMediaCleanup(
    private val db: RoomDatabase,
    private val organizationDao: MediaOrganizationDao,
    private val customAlbumDao: CustomAlbumDao,
    private val mediaLocationDao: MediaLocationDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : PermanentMediaCleanup {

    override suspend fun cleanup(ids: Collection<Long>) {
        val unique = ids.toSet()
        if (unique.isEmpty()) return
        val now = clock()
        db.withTransaction {
            unique.chunked(CHUNK_SIZE).forEach { chunk ->
                organizationDao.deleteAll(chunk.map { it.toString() })
                // Touch before deleting membership: the subquery needs the rows still present.
                customAlbumDao.touchAlbumsContaining(chunk, now)
                customAlbumDao.clearCovers(chunk, now)
                customAlbumDao.removeMembers(chunk)
                mediaLocationDao.deleteAll(chunk)
                mediaLocationDao.deleteScannedAll(chunk)
            }
        }
    }

    private companion object {
        /** Half of SQLite's classic 999-variable ceiling, with room for the other bindings. */
        const val CHUNK_SIZE = 500
    }
}
