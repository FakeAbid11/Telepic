package com.telepic.data.cloud.db

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

    /** Single row lookup by stable remote identity, used to preserve trusted local metadata on refresh. */
    @Query("SELECT * FROM cloud_media_manifest WHERE chatId = :chatId AND messageId = :messageId LIMIT 1")
    suspend fun get(chatId: Long, messageId: Long): CloudMediaManifestEntity?

    @Query("SELECT COUNT(*) FROM cloud_media_manifest")
    suspend fun count(): Int

    @Query("UPDATE cloud_media_manifest SET isDownloaded = :downloaded WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun setDownloaded(chatId: Long, messageId: Long, downloaded: Boolean)

    /**
     * Recognition lookup by content hash. Deterministic single result — the earliest (lowest)
     * message id — so multiple equivalent remote records (from pre-dedup uploads) never produce a
     * new upload. Index-backed on `contentHash`; never scans the manifest into memory.
     */
    @Query("SELECT * FROM cloud_media_manifest WHERE contentHash = :hash ORDER BY messageId ASC LIMIT 1")
    suspend fun findByContentHash(hash: String): CloudMediaManifestEntity?

    /** Content-size-consistent match: the cheap prefilter on top of the hash (§12). */
    @Query(
        "SELECT * FROM cloud_media_manifest WHERE contentHash = :hash AND sizeBytes = :sizeBytes " +
            "ORDER BY messageId ASC LIMIT 1",
    )
    suspend fun findByContentHashAndSize(hash: String, sizeBytes: Long): CloudMediaManifestEntity?

    /** Persist the trusted content hash on an existing manifest row (post-upload bookkeeping). */
    @Query("UPDATE cloud_media_manifest SET contentHash = :hash, updatedAt = :updatedAt WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun setContentHash(chatId: Long, messageId: Long, hash: String?, updatedAt: Long)
}
