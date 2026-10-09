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

    /**
     * Upsert a freshly-discovered remote row WITHOUT destroying app-trusted local metadata. A
     * Cloud refresh maps Telegram messages that carry no content hash, so a plain @Upsert would null
     * out a previously-recorded SHA-256 (and reset download state / creation time). Here those
     * trusted fields are only overwritten when the incoming row supplies a real value:
     * `contentHash`, preview/original ids use COALESCE, `isDownloaded` keeps a prior true, and
     * `createdAt` is never reset. Identity (chatId+messageId) matches the retained record, so a
     * preserved hash always belongs to the same Telegram message. Repeated refreshes are idempotent.
     */
    @Query(
        "INSERT INTO cloud_media_manifest " +
            "(chatId, messageId, mediaType, mimeType, fileName, sizeBytes, width, height, durationMs, dateEpochSec, previewFileId, originalFileId, isDownloaded, contentHash, createdAt, updatedAt) " +
            "VALUES (:chatId, :messageId, :mediaType, :mimeType, :fileName, :sizeBytes, :width, :height, :durationMs, :dateEpochSec, :previewFileId, :originalFileId, :isDownloaded, :contentHash, :createdAt, :updatedAt) " +
            "ON CONFLICT(chatId, messageId) DO UPDATE SET " +
            "mediaType = excluded.mediaType, mimeType = excluded.mimeType, fileName = excluded.fileName, " +
            "sizeBytes = excluded.sizeBytes, width = excluded.width, height = excluded.height, " +
            "durationMs = excluded.durationMs, dateEpochSec = excluded.dateEpochSec, " +
            "previewFileId = COALESCE(excluded.previewFileId, cloud_media_manifest.previewFileId), " +
            "originalFileId = COALESCE(excluded.originalFileId, cloud_media_manifest.originalFileId), " +
            "isDownloaded = MAX(excluded.isDownloaded, cloud_media_manifest.isDownloaded), " +
            "contentHash = COALESCE(excluded.contentHash, cloud_media_manifest.contentHash), " +
            "createdAt = cloud_media_manifest.createdAt, updatedAt = excluded.updatedAt",
    )
    suspend fun upsertPreservingTrusted(item: CloudMediaManifestEntity)

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
