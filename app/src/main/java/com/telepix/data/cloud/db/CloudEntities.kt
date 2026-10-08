package com.telepix.data.cloud.db

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
 * (lowercase hex SHA-256) + [sizeBytes] let recognition answer "already backed up?" by content
 * across reinstall, independent of any local MediaStore id. It is populated only by Telepix's own
 * trusted backup pipeline (a real upload result or an explicit recognition associate), never from
 * an arbitrary Telegram caption.
 */
@Entity(
    tableName = "cloud_media_manifest",
    primaryKeys = ["chatId", "messageId"],
    indices = [Index(value = ["contentHash"])],
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
    val createdAt: Long,
    val updatedAt: Long,
)
