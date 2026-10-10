package com.telepic.data.organization.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-item organization state (Phase 10): Favorites, Archive, and Trash. Room stores only the user's
 * *state* — never a copy of the MediaStore library. Identity is the MediaStore id as text
 * ([localMediaId]), the same convention the backup queue uses, so a favorite/archived/trashed flag is
 * attached to the exact same stable identity as the rest of the pipeline (never a filename).
 *
 * Semantics (kept independent from backup state — these flags never touch `backup_queue`):
 * - [isFavorite]: stays visible in the normal library; surfaced in Favorites.
 * - [isArchived]: hidden from the primary Photos timeline; still stored and restorable.
 * - [isTrashed]: hidden from normal browsing; recoverable until permanently deleted.
 * - [deletedFromStore]: honest record of whether the underlying file was actually removed from
 *   MediaStore (via the system delete flow), as distinct from the app-level trash flag alone.
 *
 * Null timestamps mean the state has never been entered; timestamps record the last change for
 * ordering and future auto-empty rules.
 */
@Entity(
    tableName = "media_organization",
    indices = [Index(value = ["isFavorite"]), Index(value = ["isArchived"]), Index(value = ["isTrashed"])],
)
data class MediaOrganizationEntity(
    @PrimaryKey val localMediaId: String,
    val isFavorite: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    val favoriteAt: Long? = null,
    val archivedAt: Long? = null,
    val trashedAt: Long? = null,
    val deletedFromStore: Boolean = false,
    val updatedAt: Long,
)

/** A minimal id + boolean projection used to build the UI's batched state maps (no full-row load). */
data class OrganizationFlagsRow(
    val localMediaId: String,
    val isFavorite: Boolean,
    val isArchived: Boolean,
    val isTrashed: Boolean,
)
