package com.telepic.data.organization

import com.telepic.data.organization.db.MediaOrganizationDao
import com.telepic.data.organization.db.MediaOrganizationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Durable, app-private organization state (Favorites / Archive / Trash) keyed by the MediaStore id.
 *
 * It stores ONLY the user's flags — never the library — and is fully independent of backup state:
 * favoriting/archiving/trashing never writes to `backup_queue` and never changes an item's remote
 * identity or cloud status. Screens read batched id-sets (no per-tile query); the timeline filters
 * hidden ids at the MediaStore query itself.
 */
interface MediaOrganizationRepository {
    val favoriteIds: Flow<Set<Long>>
    val archivedIds: Flow<Set<Long>>
    val trashedIds: Flow<Set<Long>>

    /** Ids the primary timeline must exclude (archived OR trashed). Read at query time by the loader. */
    suspend fun hiddenIds(): Set<Long>

    suspend fun isFavorite(id: Long): Boolean
    suspend fun isArchived(id: Long): Boolean
    suspend fun isTrashed(id: Long): Boolean

    suspend fun setFavorite(id: Long, favorite: Boolean)
    suspend fun setArchived(id: Long, archived: Boolean)
    suspend fun moveToTrash(id: Long)
    suspend fun restoreFromTrash(id: Long)

    /** Records that the underlying file was actually removed from MediaStore (vs an app-level flag). */
    suspend fun markDeletedFromStore(id: Long)

    /** Drop all organization state for an id (after a confirmed permanent delete). */
    suspend fun remove(id: Long)
}

class DefaultMediaOrganizationRepository(
    private val dao: MediaOrganizationDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : MediaOrganizationRepository {

    override val favoriteIds: Flow<Set<Long>> = dao.favoriteIds().map { it.toIdSet() }
    override val archivedIds: Flow<Set<Long>> = dao.archivedIds().map { it.toIdSet() }
    override val trashedIds: Flow<Set<Long>> = dao.trashedIds().map { it.toIdSet() }

    override suspend fun hiddenIds(): Set<Long> = dao.hiddenIds().toIdSet()

    override suspend fun isFavorite(id: Long): Boolean = dao.get(id.key())?.isFavorite == true

    override suspend fun isArchived(id: Long): Boolean = dao.get(id.key())?.isArchived == true

    override suspend fun isTrashed(id: Long): Boolean = dao.get(id.key())?.isTrashed == true

    override suspend fun setFavorite(id: Long, favorite: Boolean) = mutate(id) { row ->
        row.copy(isFavorite = favorite, favoriteAt = if (favorite) clock() else null)
    }

    override suspend fun setArchived(id: Long, archived: Boolean) = mutate(id) { row ->
        row.copy(isArchived = archived, archivedAt = if (archived) clock() else null)
    }

    override suspend fun moveToTrash(id: Long) = mutate(id) { row ->
        row.copy(isTrashed = true, trashedAt = clock())
    }

    override suspend fun restoreFromTrash(id: Long) = mutate(id) { row ->
        row.copy(isTrashed = false, trashedAt = null)
    }

    override suspend fun markDeletedFromStore(id: Long) = mutate(id) { row ->
        row.copy(deletedFromStore = true)
    }

    override suspend fun remove(id: Long) = dao.delete(id.key())

    private suspend fun mutate(id: Long, transform: (MediaOrganizationEntity) -> MediaOrganizationEntity) {
        val now = clock()
        val existing = dao.get(id.key())
        val base = existing ?: MediaOrganizationEntity(localMediaId = id.key(), updatedAt = now)
        dao.upsert(transform(base).copy(updatedAt = now))
    }

    private fun Long.key(): String = toString()

    private fun List<String>.toIdSet(): Set<Long> = mapNotNull { it.toLongOrNull() }.toSet()
}
