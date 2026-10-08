package com.telepix.data.cloud.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Access to the single persisted cloud destination row. */
@Dao
interface CloudDestinationDao {
    @Query("SELECT * FROM cloud_destination WHERE provider = :provider LIMIT 1")
    suspend fun find(provider: String): CloudDestinationEntity?

    @Upsert
    suspend fun upsert(entity: CloudDestinationEntity)

    @Query("DELETE FROM cloud_destination WHERE provider = :provider")
    suspend fun delete(provider: String)
}

/** Access to the cloud media manifest (remote identity = chatId + messageId). */
@Dao
interface CloudMediaManifestDao {
    @Query(
        "SELECT * FROM cloud_media_manifest " +
            "ORDER BY dateEpochSec DESC, messageId DESC",
    )
    fun observeAll(): Flow<List<CloudMediaManifestEntity>>

    /** Upsert preserves the composite-key identity, so re-scanning pages does not duplicate. */
    @Upsert
    suspend fun upsertAll(items: List<CloudMediaManifestEntity>)

    @Query("SELECT COUNT(*) FROM cloud_media_manifest")
    suspend fun count(): Int

    @Query("UPDATE cloud_media_manifest SET isDownloaded = :downloaded WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun setDownloaded(chatId: Long, messageId: Long, downloaded: Boolean)
}
