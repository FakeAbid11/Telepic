package com.telepix.data.cloud.db

import androidx.room.Entity
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
 * A discovered remote media record (the cloud manifest foundation). Stable identity is the
 * (chatId, messageId) composite — never filename. [contentHash] is reserved for the future
 * recognition/dedup phase and is never computed in Phase 5.
 */
@Entity(tableName = "cloud_media_manifest", primaryKeys = ["chatId", "messageId"])
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
