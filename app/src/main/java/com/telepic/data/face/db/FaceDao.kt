package com.telepic.data.face.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Access to detected faces.
 *
 * Every write is batched. The indexer produces one chunk's worth of faces at a time (see
 * `FaceIndexer`), and a per-face insert made a 10,000-photo first scan issue 15,000 separate
 * transactions — the same mistake `MediaLocationDao` documents and fixes with `upsertAll`.
 */
@Dao
interface FaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(faces: List<FaceEntity>): List<Long>

    @Query("SELECT * FROM face WHERE localMediaId = :localMediaId")
    suspend fun facesIn(localMediaId: Long): List<FaceEntity>

    @Query("SELECT * FROM face WHERE faceId = :faceId")
    suspend fun byId(faceId: Long): FaceEntity?

    @Query("SELECT * FROM face WHERE personId = :personId ORDER BY faceId")
    suspend fun facesOf(personId: Long): List<FaceEntity>

    @Query("SELECT * FROM face ORDER BY faceId")
    suspend fun all(): List<FaceEntity>

    @Query("SELECT * FROM face WHERE personId IS NULL")
    suspend fun unassignedFaces(): List<FaceEntity>

    @Query("SELECT COUNT(*) FROM face")
    suspend fun count(): Int

    /** Observable version for the People tab's state, so it reacts to a running scan. */
    @Query("SELECT COUNT(*) FROM face")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM face WHERE personId = :personId")
    suspend fun countFor(personId: Long): Int

    /**
     * Face counts grouped by person.
     *
     * One query for the whole grid. A `COUNT(*)` per card would be a few hundred queries per emission
     * on a library with a few hundred people, which is the same mistake `observeCount` exists to
     * avoid in the other direction.
     */
    @Query("SELECT personId, COUNT(*) AS count FROM face WHERE personId IS NOT NULL GROUP BY personId")
    fun observeCountsByPerson(): Flow<List<PersonFaceCount>>

    @Query("SELECT * FROM face WHERE personId = :personId ORDER BY faceId")
    fun observeFacesOf(personId: Long): Flow<List<FaceEntity>>

    /**
     * One row per person: the face their card should crop to.
     *
     * `COALESCE(coverFaceId, MIN(faceId))` is the fallback, in SQL rather than in Kotlin, so a person
     * whose cover was retired by a rescan still gets a face instead of an empty card — and so the
     * grid never has to issue a second query to find one. The join to `person` is what makes this
     * observable: changing a cover or deleting a person re-emits it.
     *
     * Deliberately does **not** select `embedding`. A card needs a box and a photo id; deserialising
     * 2 KB of floats per person to draw a thumbnail would be the single most wasteful thing the grid
     * does.
     */
    @Query(
        "SELECT p.personId AS personId, f.faceId AS faceId, f.localMediaId AS localMediaId, " +
            "f.`left` AS `left`, f.top AS top, f.`right` AS `right`, f.bottom AS bottom, " +
            "f.confidence AS confidence " +
            "FROM person p JOIN face f ON f.faceId = " +
            "COALESCE(p.coverFaceId, (SELECT MIN(f2.faceId) FROM face f2 WHERE f2.personId = p.personId))",
    )
    fun observeCovers(): Flow<List<PersonCover>>

    @Query("UPDATE face SET personId = :personId WHERE faceId IN (:faceIds)")
    suspend fun assignTo(personId: Long?, faceIds: List<Long>)

    /**
     * Reassigns many faces in one transaction.
     *
     * A rebuild assigns every face, so this runs once per clustering pass rather than once per face.
     * Grouped by target person because `IN (...)` cannot vary per row; the alternative — one `UPDATE`
     * per face — was thousands of statements for a single rebuild.
     */
    @Transaction
    suspend fun assignAll(assignments: List<Pair<Long, Long>>) {
        assignments.groupBy({ it.second }, { it.first }).forEach { (personId, faceIds) ->
            faceIds.chunked(ASSIGN_CHUNK).forEach { chunk -> assignTo(personId, chunk) }
        }
    }

    /** Distinguishes "this photo has no faces" from "this photo has not been scanned". */
    @Query("SELECT localMediaId FROM face_scan")
    suspend fun scannedIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markScanned(entries: List<FaceScanEntity>)

    @Query("DELETE FROM face WHERE faceId = :faceId")
    suspend fun delete(faceId: Long)

    @Query("DELETE FROM face WHERE localMediaId IN (:localMediaIds)")
    suspend fun deleteForMedia(localMediaIds: List<Long>)

    @Query("DELETE FROM face_scan WHERE localMediaId IN (:localMediaIds)")
    suspend fun deleteScans(localMediaIds: List<Long>)

    /**
     * Drops scan memory wholesale so the next run re-examines every photo.
     *
     * Needed after the models are replaced: an embedding produced by the old model is not comparable
     * with one produced by the new model, so "already scanned" stops meaning "already embedded in the
     * current space". Faces are cleared alongside — see `FaceRepository.removeAll`.
     */
    @Query("DELETE FROM face_scan")
    suspend fun clearScans()

    @Query("DELETE FROM face")
    suspend fun clearFaces()

    companion object {
        /** Well under SQLite's 999-variable ceiling, with room for the other bindings. */
        const val ASSIGN_CHUNK = 500
    }
}

/** One row of [FaceDao.observeCountsByPerson]. */
data class PersonFaceCount(
    val personId: Long,
    val count: Int,
)

/**
 * One row of [FaceDao.observeCovers]: which face a person's card should crop to, without the
 * embedding.
 *
 * A projection rather than a [FaceEntity] because the grid draws a thumbnail, not an identity: the
 * 512-float vector is the largest column in the table and would be read for every person on every
 * emission.
 */
data class PersonCover(
    val personId: Long,
    val faceId: Long,
    val localMediaId: Long,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
)
