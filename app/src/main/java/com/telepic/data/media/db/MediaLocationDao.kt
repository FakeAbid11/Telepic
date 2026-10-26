package com.telepic.data.media.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Access to the geotagged-location cache. Bounded, incremental writes; one batched read flow. */
@Dao
interface MediaLocationDao {

    @Query("SELECT * FROM media_location ORDER BY extractedAt DESC")
    fun observeAll(): Flow<List<MediaLocationEntity>>

    /** Ids that already have a cached coordinate — the items the map can draw a pin for. */
    @Query("SELECT localMediaId FROM media_location")
    suspend fun cachedIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaLocationEntity)

    /**
     * Batch write for one scan page. A per-item [upsert] made a first sweep of a geotagged-heavy
     * library issue one transaction per geotagged file; the page is already in memory, so it costs
     * nothing to write it in one statement.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<MediaLocationEntity>)

    @Query("DELETE FROM media_location WHERE localMediaId = :localMediaId")
    suspend fun delete(localMediaId: Long)

    /** Removes cached coordinates for confirmed permanent deletions in one statement. */
    @Query("DELETE FROM media_location WHERE localMediaId IN (:localMediaIds)")
    suspend fun deleteAll(localMediaIds: List<Long>)

    /** Prunes cached locations for ids no longer in the library (kept cheap and bounded). */
    @Query("DELETE FROM media_location")
    suspend fun clear()

    /**
     * Every id already examined for GPS, coordinate or not. This is the scan's memory: it is what lets
     * a sweep walk past a coordinate-less file instead of re-reading its EXIF on every open.
     */
    @Query("SELECT localMediaId FROM media_location_scan")
    suspend fun scannedIds(): List<Long>

    /**
     * Marks a whole page as examined in one transaction. Batched deliberately: a per-item insert made
     * a first sweep of a large library thousands of separate writes.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markScanned(entities: List<MediaLocationScanEntity>)

    @Query("DELETE FROM media_location_scan WHERE localMediaId = :localMediaId")
    suspend fun deleteScanned(localMediaId: Long)

    /** Drops scan memory for confirmed permanent deletions in one statement. */
    @Query("DELETE FROM media_location_scan WHERE localMediaId IN (:localMediaIds)")
    suspend fun deleteScannedAll(localMediaIds: List<Long>)

    @Query("DELETE FROM media_location_scan")
    suspend fun clearScanned()
}
