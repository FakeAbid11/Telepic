package com.telepic.data.face.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One clustered person in the People tab.
 *
 * A person exists whether or not anybody has named them: [name] is null until the user types one,
 * and the UI renders "Person 1" from the id. Keeping the naming optional is what lets the indexer
 * create groups on its own without ever claiming to know who someone is.
 *
 * [coverFaceId] is a *hint*, not a constraint. It points at the face the tab should crop for this
 * person's card — normally the largest, most front-facing detection. It is deliberately not a
 * foreign key: a rescan can delete and recreate face rows, and a hard reference would either block
 * that delete or cascade a whole person away because their cover happened to be re-detected.
 */
@Entity(tableName = "person")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val personId: Long = 0,
    /** User-assigned name, or null for an unnamed group. */
    val name: String? = null,
    /** The face to crop for this person's card. See the class KDoc for why this is not a FK. */
    val coverFaceId: Long? = null,
    /**
     * Hides the group without deleting it. Same idea as `media_organization.isArchived`: a hidden
     * person keeps their faces (so a later unhide costs nothing) but is not shown in the grid.
     */
    val isHidden: Boolean = false,
    val createdAt: Long,
)

/**
 * One detected face: where it is, and the embedding that identifies it.
 *
 * The box is stored **normalized** (0..1 relative to the decoded image) rather than in pixels.
 * The indexer decodes at a bounded size (see `FaceIndexer`), so pixel coordinates would be tied to
 * whatever that bound happened to be and would silently mean something different after a change to
 * it. Normalized coordinates survive any decode size, which is what the crop UI needs.
 *
 * [embedding] is a 512-float GhostFaceNet vector serialised as little-endian float32 — see
 * [FaceEmbeddingCodec]. It is a `ByteArray` rather than 512 columns because the clustering code
 * treats it as one opaque value; nothing ever queries an individual component.
 *
 * [personId] is null for a face the clusterer could not place (or has not placed yet). Nullable
 * rather than a sentinel id keeps "unassigned" distinguishable from "assigned to person 0".
 *
 * **No foreign key to `person`.** The clusterer reassigns and merges people constantly, and an
 * `ON DELETE CASCADE` would delete faces whenever a merge retired a person row — destroying the very
 * embeddings the merge was supposed to preserve. Reassignment is an explicit `UPDATE` instead, and
 * the "delete a person" path nulls their faces first. The index on [personId] keeps that a seek.
 */
@Entity(
    tableName = "face",
    indices = [
        Index("localMediaId"),
        Index("personId"),
    ],
)
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val faceId: Long = 0,
    /** MediaStore id of the photo this face was found in. */
    val localMediaId: Long,
    /** Normalized bounding box, 0..1 of the decoded image's width/height. */
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** 512 × float32, little-endian. See [FaceEmbeddingCodec]. */
    val embedding: ByteArray,
    /** The person this face currently belongs to, or null if unassigned. */
    val personId: Long? = null,
    /** Detector confidence, 0..1. Used to pick a cover and to rank crops. */
    val confidence: Float,
    val detectedAt: Long,
) {
    // Room's generated equals/hashCode would compare `embedding` by reference, which makes two
    // structurally identical rows unequal. Data classes holding a ByteArray are a known trap and
    // detekt's ArrayPrimitive rule is not the only reason to spell these out.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceEntity) return false
        return faceId == other.faceId &&
            localMediaId == other.localMediaId &&
            left == other.left &&
            top == other.top &&
            right == other.right &&
            bottom == other.bottom &&
            embedding.contentEquals(other.embedding) &&
            personId == other.personId &&
            confidence == other.confidence &&
            detectedAt == other.detectedAt
    }

    override fun hashCode(): Int {
        var result = faceId.hashCode()
        result = 31 * result + localMediaId.hashCode()
        result = 31 * result + left.hashCode()
        result = 31 * result + top.hashCode()
        result = 31 * result + right.hashCode()
        result = 31 * result + bottom.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + (personId?.hashCode() ?: 0)
        result = 31 * result + confidence.hashCode()
        result = 31 * result + detectedAt.hashCode()
        return result
    }
}

/**
 * Records every photo the face indexer has already examined — **including photos it found no faces
 * in**.
 *
 * This table is not optional bookkeeping, it is the only reason a second scan is cheap. A photo with
 * no faces produces no [FaceEntity] rows, so "has this been scanned?" cannot be answered from the
 * face table: without this record every scan would re-decode every screenshot and landscape photo in
 * the library forever. It is the exact same distinction `media_location_scan` draws for the map
 * sweep, and it exists for the same reason.
 */
@Entity(tableName = "face_scan")
data class FaceScanEntity(
    @PrimaryKey val localMediaId: Long,
    val scannedAt: Long,
)
