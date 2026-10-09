package com.telepix.data.organization.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Access to the per-item organization state. Everything reads through batched flows or keyed
 * single-row queries so the UI never runs a per-tile query and never loads the whole library.
 */
@Dao
interface MediaOrganizationDao {

    /** Batched id + flags snapshot that feeds the Favorites / Archive / Trash state maps. */
    @Query("SELECT localMediaId, isFavorite, isArchived, isTrashed FROM media_organization")
    fun observeFlags(): Flow<List<OrganizationFlagsRow>>

    /** Ids hidden from the primary library (archived OR trashed) — used for DB-side timeline filtering. */
    @Query("SELECT localMediaId FROM media_organization WHERE isArchived = 1 OR isTrashed = 1")
    suspend fun hiddenIds(): List<String>

    @Query("SELECT localMediaId FROM media_organization WHERE isFavorite = 1 ORDER BY favoriteAt DESC")
    fun favoriteIds(): Flow<List<String>>

    @Query("SELECT localMediaId FROM media_organization WHERE isArchived = 1 ORDER BY archivedAt DESC")
    fun archivedIds(): Flow<List<String>>

    @Query("SELECT localMediaId FROM media_organization WHERE isTrashed = 1 ORDER BY trashedAt DESC")
    fun trashedIds(): Flow<List<String>>

    @Query("SELECT * FROM media_organization WHERE localMediaId = :localMediaId")
    suspend fun get(localMediaId: String): MediaOrganizationEntity?

    @Upsert
    suspend fun upsert(entity: MediaOrganizationEntity)

    /**
     * Removes the organization row entirely. Used after a permanent delete or when an item is no
     * longer tracked; deleting the row never touches `backup_queue` (state stays independent).
     */
    @Query("DELETE FROM media_organization WHERE localMediaId = :localMediaId")
    suspend fun delete(localMediaId: String)

    /** Drops favorite rows whose media no longer exists, keeping state tidy without a full scan. */
    @Query("SELECT localMediaId FROM media_organization")
    suspend fun allIds(): List<String>
}
