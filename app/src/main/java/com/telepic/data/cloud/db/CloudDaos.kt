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

    /**
     * One batch lookup for a page of discovered rows, so preserving trusted local metadata costs one
     * query per page instead of one per row. Backed by the `(chatId, messageId)` primary key.
     */
    @Query("SELECT * FROM cloud_media_manifest WHERE chatId = :chatId AND messageId IN (:messageIds)")
    suspend fun findByIds(chatId: Long, messageIds: List<Long>): List<CloudMediaManifestEntity>

    /**
     * Every message id this install already records for [chatId].
     *
     * This is the manifest rebuild's stop signal: walking the channel's history is only affordable
     * because the walk can halt the moment it reaches rows that are already known. An empty result
     * (a fresh install, or a reinstall) is what makes the walk cover the whole channel once.
     */
    @Query("SELECT messageId FROM cloud_media_manifest WHERE chatId = :chatId")
    suspend fun messageIds(chatId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM cloud_media_manifest")
    suspend fun count(): Int

    /** Drops all manifest rows. Used only when the persisted destination belongs to another account. */
    @Query("DELETE FROM cloud_media_manifest")
    suspend fun clearAll()

    @Query("UPDATE cloud_media_manifest SET isDownloaded = :downloaded WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun setDownloaded(chatId: Long, messageId: Long, downloaded: Boolean)

    /**
     * Recognition lookup by content identity. Deterministic single result — the earliest (lowest)
     * message id — so multiple equivalent remote records (from pre-dedup uploads) never produce a
     * new upload. Index-backed on `contentHash`; never scans the manifest into memory.
     *
     * The size is compared against `contentSizeBytes` — the byte count the hash was computed over —
     * with `COALESCE` back to `sizeBytes` for rows written before that column existed (migration
     * 9 -> 10). Comparing against `sizeBytes` alone silently missed every photo, because Telegram
     * recompresses photos and reports its own size for them.
     */
    @Query(
        "SELECT * FROM cloud_media_manifest WHERE contentHash = :hash " +
            "AND COALESCE(contentSizeBytes, sizeBytes) = :sizeBytes " +
            "ORDER BY messageId ASC LIMIT 1",
    )
    suspend fun findByContentHashAndSize(hash: String, sizeBytes: Long): CloudMediaManifestEntity?

    /**
     * The reinstall-recovery fallback: a manifest row that carries **no** content hash but whose
     * filename and byte count match the local item exactly.
     *
     * This exists for media backed up *before* Telepic stamped an identity into the caption (see
     * `CloudBackupMarker`), which is unrecoverable by hash. It is restricted to `contentHash IS NULL`
     * on purpose: a row that already has a hash must be matched by hash, because that is the only
     * check that proves the *bytes* are the same — a filename is not identity, and letting a named
     * row match here would turn a name collision into a skipped upload.
     *
     * Only videos, GIFs and documents carry a filename on the Telegram side; a discovered photo has
     * none, so this can never match one. That gap is closed by the caption marker instead, not by
     * guessing.
     */
    @Query(
        "SELECT * FROM cloud_media_manifest WHERE contentHash IS NULL " +
            "AND fileName = :fileName AND sizeBytes = :sizeBytes " +
            "ORDER BY messageId ASC LIMIT 1",
    )
    suspend fun findHashlessByNameAndSize(fileName: String, sizeBytes: Long): CloudMediaManifestEntity?

    /**
     * Persist a content identity onto an existing manifest row — the post-upload bookkeeping write,
     * and the recovery write that upgrades a hashless row found by
     * [findHashlessByNameAndSize] so the next scan matches it exactly.
     *
     * Guarded on `contentHash IS NULL` so a row that already carries a trusted hash is never
     * rewritten; the affected-row count therefore reports whether the upgrade was applied, and 0 is
     * the honest "already recorded" answer rather than an error.
     */
    @Query(
        "UPDATE cloud_media_manifest SET contentHash = :hash, contentSizeBytes = :sizeBytes, updatedAt = :updatedAt " +
            "WHERE chatId = :chatId AND messageId = :messageId AND contentHash IS NULL",
    )
    suspend fun setContentIdentity(
        chatId: Long,
        messageId: Long,
        hash: String,
        sizeBytes: Long,
        updatedAt: Long,
    ): Int
}
