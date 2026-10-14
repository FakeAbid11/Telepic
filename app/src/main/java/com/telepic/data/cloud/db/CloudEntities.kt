package com.telepic.data.cloud.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted, validated Telegram cloud destination. A single canonical row keyed by provider so
 * there are never duplicate destinations. Cached here only — Telegram remains the source of truth.
 */
@Entity(tableName = "cloud_destination")
data class CloudDestinationEntity(
    @PrimaryKey val provider: String,
    val chatId: Long,
    val title: String,
    val validated: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * A discovered or uploaded remote media record (the cloud manifest — the long-term recognition
 * source). Stable identity is the (chatId, messageId) composite — never filename. [contentHash]
 * (lowercase hex SHA-256) + [contentSizeBytes] let recognition answer "already backed up?" by content
 * across reinstall, independent of any local MediaStore id.
 *
 * Two ways those two columns get filled, and the difference matters:
 * - **This device's own upload** writes them from the upload result directly.
 * - **A manifest rebuilt from the channel's history** (a reinstall) recovers them from the message
 *   caption, which `CloudBackupMarker` stamps on every upload. Without that step the rebuilt rows had
 *   no hash at all, recognition could not match them, and every item was re-uploaded. Recovering them
 *   also requires the rebuild to walk the **whole** history: `TelegramCloudRepository.refreshLocked`
 *   pages older messages until it reaches rows this install already holds, because a rebuild limited
 *   to the newest page would leave every older row absent and re-upload the rest of the library.
 *
 * [contentSizeBytes] is the byte count the hash was computed over; [sizeBytes] is what Telegram
 * reports for the stored file. They disagree for photos (Telegram recompresses), which is why the
 * recognition match is size-checked against the former, via `COALESCE` so rows written before this
 * column existed still match on [sizeBytes].
 *
 * Both stay null when nothing trustworthy is available, and recognition treats that as "unknown"
 * rather than "not backed up".
 */
@Entity(
    tableName = "cloud_media_manifest",
    primaryKeys = ["chatId", "messageId"],
    // `dateEpochSec` was added in migration 8 -> 9: the manifest is listed ordered by it, so without
    // the index every cloud-library read sorted a full scan.
    indices = [Index(value = ["contentHash"]), Index(value = ["dateEpochSec"])],
)
data class CloudMediaManifestEntity(
    val chatId: Long,
    val messageId: Long,
    val mediaType: String,
    val mimeType: String?,
    val fileName: String?,
    val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val dateEpochSec: Long?,
    val previewFileId: Int?,
    val originalFileId: Int?,
    val isDownloaded: Boolean,
    val contentHash: String?,
    /** Added in migration 9 -> 10. Null for rows written before it existed. */
    val contentSizeBytes: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)
