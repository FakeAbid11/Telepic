package com.telepic.data.media.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room access to user-created albums.
 *
 * Reads are batched flows so the Albums grid costs one query regardless of how many albums exist —
 * never a per-album count. Writes are targeted statements rather than row rewrites, so renaming an
 * album cannot clobber its cover and adding media cannot clobber its name.
 */
@Dao
interface CustomAlbumDao {

    /** Every album with its member count, most recently touched first. */
    @Query(
        "SELECT a.id AS id, a.name AS name, a.coverMediaId AS coverMediaId, " +
            "COUNT(m.localMediaId) AS count " +
            "FROM custom_album a " +
            "LEFT JOIN custom_album_media m ON m.albumId = a.id " +
            "GROUP BY a.id, a.name, a.coverMediaId, a.updatedAt " +
            "ORDER BY a.updatedAt DESC",
    )
    fun observeAlbums(): Flow<List<CustomAlbumRow>>

    /** The album's current name, or null when it has been deleted. Keeps an open screen in step. */
    @Query("SELECT name FROM custom_album WHERE id = :albumId")
    fun observeName(albumId: Long): Flow<String?>

    /** Member ids, most recently added first. */
    @Query("SELECT localMediaId FROM custom_album_media WHERE albumId = :albumId ORDER BY addedAt DESC")
    fun observeMediaIds(albumId: Long): Flow<List<Long>>

    /** Member ids, most recently added first — the one-shot read the paging loader uses. */
    @Query("SELECT localMediaId FROM custom_album_media WHERE albumId = :albumId ORDER BY addedAt DESC")
    suspend fun mediaIds(albumId: Long): List<Long>

    @Query("SELECT name FROM custom_album WHERE id = :albumId")
    suspend fun name(albumId: Long): String?

    @Insert
    suspend fun insertAlbum(entity: CustomAlbumEntity): Long

    @Query("UPDATE custom_album SET name = :name, updatedAt = :now WHERE id = :albumId")
    suspend fun rename(albumId: Long, name: String, now: Long)

    /** Writes the cover hint and bumps `updatedAt`, so the album moves to the front of the grid. */
    @Query("UPDATE custom_album SET coverMediaId = :coverMediaId, updatedAt = :now WHERE id = :albumId")
    suspend fun setCover(albumId: Long, coverMediaId: Long?, now: Long)

    /** Drops the cover hint when it names the member being removed — it would otherwise show a photo
     * the album no longer holds. Leaves `updatedAt` alone; [touch] owns that. */
    @Query("UPDATE custom_album SET coverMediaId = NULL WHERE id = :albumId AND coverMediaId = :localMediaId")
    suspend fun clearCoverIfMatches(albumId: Long, localMediaId: Long)

    /** Marks the album as just changed, so the grid keeps ordering by most recently used. */
    @Query("UPDATE custom_album SET updatedAt = :now WHERE id = :albumId")
    suspend fun touch(albumId: Long, now: Long)

    @Query("DELETE FROM custom_album WHERE id = :albumId")
    suspend fun deleteAlbum(albumId: Long)

    /**
     * Adds membership rows. The composite primary key plus `IGNORE` makes a repeat a no-op, so the
     * caller does not have to guard against double-taps. The return is deliberately `Unit`: Room's
     * `-1`-per-ignored-row convention for a `List<Long>` return is easy to read as "inserted", and the
     * caller needs an exact "how many were new" — it asks [memberIds] first instead.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembers(rows: List<CustomAlbumMediaEntity>)

    /** Every member id, in no particular order — used to work out what is genuinely new. */
    @Query("SELECT localMediaId FROM custom_album_media WHERE albumId = :albumId")
    suspend fun memberIds(albumId: Long): List<Long>

    @Query("DELETE FROM custom_album_media WHERE albumId = :albumId AND localMediaId = :localMediaId")
    suspend fun removeMember(albumId: Long, localMediaId: Long)

    @Query("DELETE FROM custom_album_media WHERE albumId = :albumId")
    suspend fun clearMembers(albumId: Long)

    /**
     * Removes one deleted media id from every album that held it.
     *
     * A permanent MediaStore delete must not leave a membership row behind: the album count joins
     * this table and would otherwise keep counting a file that no longer exists.
     */
    @Query("DELETE FROM custom_album_media WHERE localMediaId IN (:localMediaIds)")
    suspend fun removeMembers(localMediaIds: List<Long>): Int

    /** Clears a cover hint that names a deleted media item, and marks the album as changed. */
    @Query(
        "UPDATE custom_album SET coverMediaId = NULL, updatedAt = :now " +
            "WHERE coverMediaId IN (:localMediaIds)",
    )
    suspend fun clearCovers(localMediaIds: List<Long>, now: Long): Int

    /** Moves albums that lost a member to the front of the grid, matching any other content edit. */
    @Query(
        "UPDATE custom_album SET updatedAt = :now WHERE id IN (" +
            "SELECT DISTINCT albumId FROM custom_album_media WHERE localMediaId IN (:localMediaIds))",
    )
    suspend fun touchAlbumsContaining(localMediaIds: List<Long>, now: Long): Int
}
