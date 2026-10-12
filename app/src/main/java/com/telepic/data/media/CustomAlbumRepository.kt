package com.telepic.data.media

import android.net.Uri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.telepic.data.media.db.CustomAlbumDao
import com.telepic.data.media.db.CustomAlbumEntity
import com.telepic.data.media.db.CustomAlbumMediaEntity
import com.telepic.domain.media.CustomAlbum
import com.telepic.domain.media.PhotosItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * User-created albums: the ones the user builds by hand, as opposed to the folder albums
 * [AlbumRepository] derives from MediaStore buckets.
 *
 * Kept as its own contract rather than folded into [AlbumRepository] because the two have genuinely
 * different semantics — one is read-only and filesystem-defined, the other is writable and defined by
 * the user — and because a folder album can be neither created nor deleted from Telepic. A single
 * interface would have made every implementation stub out half of it.
 */
interface CustomAlbumRepository {
    /** Every album with its member count and resolved cover, most recently touched first. */
    val albums: Flow<List<CustomAlbum>>

    /**
     * Creates an album and returns its id, or null when [name] is blank.
     *
     * Null rather than a throw: a blank name is a caller mistake the UI already prevents, and the
     * honest answer is "no album was created" rather than an exception that would close the app.
     */
    suspend fun create(name: String): Long?

    /** Renames an album. A blank name is ignored — the album keeps the name it had. */
    suspend fun rename(albumId: Long, name: String)

    /** Deletes an album and, by cascade, its membership. The media itself is untouched. */
    suspend fun delete(albumId: Long)

    /** Adds [mediaIds] to the album; returns how many were genuinely new (already-present ones are not). */
    suspend fun addMedia(albumId: Long, mediaIds: List<Long>): Int

    /** Removes one member. The photo stays on the device and in the library — only membership goes. */
    suspend fun removeMedia(albumId: Long, localMediaId: Long)

    /** The album's current name, or null once it has been deleted. */
    fun observeName(albumId: Long): Flow<String?>

    /** The album's member ids, most recently added first. */
    fun observeMediaIds(albumId: Long): Flow<List<Long>>

    /** The member ids as a one-shot read, for the paging loader. */
    suspend fun mediaIds(albumId: Long): List<Long>

    /**
     * The album's member ids that still resolve, ordered newest-first like the timeline.
     *
     * This is the Viewer's neighbour source: next/previous inside a custom album must walk the album,
     * not the timeline, and it must walk it in the same order the grid displays.
     */
    suspend fun orderedMediaIds(albumId: Long): List<Long>

    /** This album's contents as a paged, day-grouped stream — the same shape as a folder album. */
    fun mediaInAlbum(albumId: Long): Flow<PagingData<PhotosItem>>

    /**
     * Invalidates the current contents pager so the next load re-reads membership and the hidden set.
     *
     * Needed for the same reason [AlbumRepository.refresh] is: a `cachedIn` pager never re-queries on
     * its own, so archiving or trashing a photo from the Viewer would leave it listed here.
     */
    fun refresh()
}

/**
 * [CustomAlbumRepository] over Room, with covers resolved through [LocalMediaLookup].
 *
 * Covers are resolved in **one** batched lookup per emission, not one query per album: the grid asks
 * for every album at once, and a per-album query would make the screen's cost scale with the number of
 * albums the user has made.
 */
class DefaultCustomAlbumRepository(
    private val dao: CustomAlbumDao,
    private val lookup: LocalMediaLookup,
    private val hiddenIdsProvider: suspend () -> Set<Long> = { emptySet() },
    private val clock: () -> Long = System::currentTimeMillis,
) : CustomAlbumRepository {

    /**
     * The most recently created contents pager. One album screen is open at a time, so a single field
     * is enough — the same design [MediaStoreAlbumRepository] and [LocalMediaRepositoryImpl] use.
     */
    private var currentSource: MediaPagingSource? = null

    override val albums: Flow<List<CustomAlbum>> = dao.observeAlbums().map { rows ->
        val covers = resolveCovers(rows.mapNotNull { it.coverMediaId })
        rows.map { row ->
            CustomAlbum(
                id = row.id,
                name = row.name,
                count = row.count,
                coverUri = row.coverMediaId?.let { covers[it] },
            )
        }
    }

    override suspend fun create(name: String): Long? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val now = clock()
        return dao.insertAlbum(CustomAlbumEntity(name = trimmed, createdAt = now, updatedAt = now))
    }

    override suspend fun rename(albumId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        dao.rename(albumId, trimmed, clock())
    }

    override suspend fun delete(albumId: Long) {
        // Membership rows go with the album through the foreign key's ON DELETE CASCADE.
        dao.deleteAlbum(albumId)
    }

    override suspend fun addMedia(albumId: Long, mediaIds: List<Long>): Int {
        if (mediaIds.isEmpty()) return 0
        // Deduplicate within the request as well as against the table. The primary key already makes a
        // repeat a no-op, but reporting "added 3" for three copies of one photo would be a lie.
        val distinct = mediaIds.distinct()
        val existing = dao.memberIds(albumId).toSet()
        val toAdd = distinct.filterNot { it in existing }
        if (toAdd.isEmpty()) return 0
        val now = clock()
        dao.insertMembers(toAdd.map { CustomAlbumMediaEntity(albumId, it, now) })
        // The cover hint is the most recently added member, which is the last one in this request.
        dao.setCover(albumId, toAdd.last(), now)
        return toAdd.size
    }

    override suspend fun removeMedia(albumId: Long, localMediaId: Long) {
        val now = clock()
        dao.removeMember(albumId, localMediaId)
        // The cover hint may now name something the album no longer holds. Clearing it is honest — the
        // card falls back to a placeholder — where leaving it would show a cover the album does not own.
        dao.clearCoverIfMatches(albumId, localMediaId)
        dao.touch(albumId, now)
    }

    override fun observeName(albumId: Long): Flow<String?> = dao.observeName(albumId)

    override fun observeMediaIds(albumId: Long): Flow<List<Long>> = dao.observeMediaIds(albumId)

    override suspend fun mediaIds(albumId: Long): List<Long> = dao.mediaIds(albumId)

    override suspend fun orderedMediaIds(albumId: Long): List<Long> = resolveOrderedMemberIds(
        memberIds = dao.mediaIds(albumId),
        lookup = lookup,
        hiddenIds = hiddenIdsProvider(),
    )

    override fun mediaInAlbum(albumId: Long): Flow<PagingData<PhotosItem>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            prefetchDistance = PREFETCH_DISTANCE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = {
            MediaPagingSource(
                CustomAlbumMediaLoader(
                    memberIdsProvider = { dao.mediaIds(albumId) },
                    lookup = lookup,
                    hiddenIdsProvider = hiddenIdsProvider,
                ),
            ).also { currentSource = it }
        },
    ).flow

    override fun refresh() {
        currentSource?.invalidate()
    }

    /**
     * One batched lookup for every album's cover.
     *
     * A failed lookup degrades to placeholders rather than failing the whole grid: an unreadable
     * provider should not make the user's albums disappear, and a missing cover is visibly a missing
     * cover, not a wrong one.
     */
    private suspend fun resolveCovers(ids: List<Long>): Map<Long, Uri> {
        if (ids.isEmpty()) return emptyMap()
        return try {
            lookup.byIdList(ids).associate { it.id to it.contentUri }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private companion object {
        const val PAGE_SIZE = 60
        const val PREFETCH_DISTANCE = 30
        const val INITIAL_LOAD_SIZE = 90
    }
}
