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

    @Query("SELECT localMediaId FROM media_location")
    suspend fun cachedIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaLocationEntity)

    @Query("DELETE FROM media_location WHERE localMediaId = :localMediaId")
    suspend fun delete(localMediaId: Long)

    /** Prunes cached locations for ids no longer in the library (kept cheap and bounded). */
    @Query("DELETE FROM media_location")
    suspend fun clear()
}
