package com.telepic.data.face.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Access to person groups.
 *
 * There is no delete-all here on purpose: dropping every person without dropping their faces would
 * leave rows pointing at ids that no longer exist. `FaceRepository.removeAll` clears faces and
 * persons together, in that order, so the orphan state is never observable.
 */
@Dao
interface PersonDao {

    /**
     * Every person, named ones first, then by id.
     *
     * `(name IS NULL)` sorts 0 before 1 in SQLite, so named people come first; `COLLATE NOCASE` keeps
     * "alice" and "Alice" adjacent rather than splitting them across the list. Id is the tiebreak so
     * the order is stable between emissions — without it the grid would reshuffle on every update.
     */
    @Query("SELECT * FROM person WHERE isHidden = 0 ORDER BY (name IS NULL), name COLLATE NOCASE, personId")
    fun observeVisible(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM person WHERE personId = :personId")
    fun observeById(personId: Long): Flow<PersonEntity?>

    @Query("SELECT * FROM person ORDER BY personId")
    suspend fun all(): List<PersonEntity>

    @Query("SELECT * FROM person WHERE personId = :personId")
    suspend fun byId(personId: Long): PersonEntity?

    @Insert
    suspend fun insert(person: PersonEntity): Long

    @Query("UPDATE person SET name = :name WHERE personId = :personId")
    suspend fun rename(personId: Long, name: String?)

    @Query("UPDATE person SET coverFaceId = :faceId WHERE personId = :personId")
    suspend fun setCover(personId: Long, faceId: Long?)

    @Query("UPDATE person SET isHidden = :hidden WHERE personId = :personId")
    suspend fun setHidden(personId: Long, hidden: Boolean)

    @Query("DELETE FROM person WHERE personId = :personId")
    suspend fun delete(personId: Long)

    @Query("DELETE FROM person")
    suspend fun clear()
}
