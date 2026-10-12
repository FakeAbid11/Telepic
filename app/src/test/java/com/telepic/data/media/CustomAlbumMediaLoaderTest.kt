package com.telepic.data.media

import android.net.Uri
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The custom-album loader's job is the part SQLite cannot do for it: a user-created album's members
 * live in Room, have to be resolved through MediaStore in chunks, and then have to be merged back into
 * one newest-first sequence.
 *
 * The merge is the piece worth testing, because it is the only part that fails silently: every chunk
 * looks correctly ordered on its own while the album as a whole is shuffled, and nothing else in the
 * app would notice.
 *
 * Robolectric rather than a plain JVM test, because building a [LocalMedia] needs `Uri.parse` — the
 * loader itself is framework-free, but its inputs are not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CustomAlbumMediaLoaderTest {

    private fun media(id: Long, dateMillis: Long) = LocalMedia(
        id = id,
        contentUri = Uri.parse("content://media/$id"),
        type = MediaType.PHOTO,
        mimeType = "image/jpeg",
        displayName = "p$id.jpg",
        dateMillis = dateMillis,
        durationMillis = null,
        width = 1,
        height = 1,
        sizeBytes = 1L,
        bucketId = null,
        bucketName = null,
        relativePath = null,
    )

    /**
     * Stands in for MediaStore: each call comes back ordered by the timeline sort, exactly as the
     * provider orders a single `_ID IN (...)` query. That is what makes the loader's own merge the
     * thing under test rather than something the fake already did for it.
     */
    private class ChunkOrderingLookup(private val items: Map<Long, LocalMedia>) : LocalMediaLookup {
        override suspend fun byId(id: Long): LocalMedia? = items[id]

        override suspend fun neighborId(id: Long, direction: NeighborDirection, bucketId: Long?): Long? = null

        override suspend fun byIdList(ids: Collection<Long>): List<LocalMedia> =
            ids.mapNotNull { items[it] }.sortedWith(MediaSortOrder.TIMELINE_DESC_COMPARATOR)
    }

    private val items = mapOf(
        1L to media(1L, dateMillis = 100L),
        2L to media(2L, dateMillis = 400L),
        3L to media(3L, dateMillis = 200L),
        4L to media(4L, dateMillis = 300L),
    )

    private fun loader(
        memberIds: List<Long> = listOf(1L, 2L, 3L, 4L),
        hidden: Set<Long> = emptySet(),
        chunkSize: Int = 2,
    ) = CustomAlbumMediaLoader(
        memberIdsProvider = { memberIds },
        lookup = ChunkOrderingLookup(items),
        hiddenIdsProvider = { hidden },
        queryChunkSize = chunkSize,
    )

    @Test
    fun `members are merged into one newest-first sequence across chunks`() = runBlocking {
        // Neither chunk is in the right global order on its own: the first holds the newest *and* the
        // oldest item, the second holds the two in between.
        assertEquals(listOf(2L, 4L, 3L, 1L), loader().load(offset = 0, limit = 10).map { it.id })
    }

    @Test
    fun `a tie on date falls back to the higher id, matching the SQL ordering`() = runBlocking {
        val tied = mapOf(
            1L to media(1L, dateMillis = 500L),
            2L to media(2L, dateMillis = 500L),
        )
        val tiedLoader = CustomAlbumMediaLoader(
            memberIdsProvider = { listOf(1L, 2L) },
            lookup = ChunkOrderingLookup(tied),
            queryChunkSize = 1,
        )
        assertEquals(listOf(2L, 1L), tiedLoader.load(0, 10).map { it.id })
    }

    @Test
    fun `paging slices the ordered sequence without repeating or skipping`() = runBlocking {
        val loader = loader()
        assertEquals(listOf(2L, 4L), loader.load(offset = 0, limit = 2).map { it.id })
        assertEquals(listOf(3L, 1L), loader.load(offset = 2, limit = 2).map { it.id })
        assertTrue(loader.load(offset = 4, limit = 2).isEmpty())
    }

    @Test
    fun `hidden members are excluded before paging, so offsets stay correct`() = runBlocking {
        // Excluded up front rather than filtered out of a finished page: a page that returns short reads
        // to Paging 3 as the end of the album, which would truncate it.
        val loader = loader(hidden = setOf(2L, 3L))
        assertEquals(listOf(4L, 1L), loader.load(offset = 0, limit = 10).map { it.id })
        assertEquals(listOf(1L), loader.load(offset = 1, limit = 10).map { it.id })
    }

    @Test
    fun `an album whose members are all hidden pages as empty`() = runBlocking {
        assertTrue(loader(hidden = setOf(1L, 2L, 3L, 4L)).load(0, 10).isEmpty())
    }

    @Test
    fun `an empty album pages as empty`() = runBlocking {
        assertTrue(loader(memberIds = emptyList()).load(0, 10).isEmpty())
    }

    @Test
    fun `a member whose file is gone is simply absent`() = runBlocking {
        // 9 is in the album but no longer resolvable — deleted outside Telepic.
        val loader = loader(memberIds = listOf(1L, 9L, 2L))
        assertEquals(listOf(2L, 1L), loader.load(offset = 0, limit = 10).map { it.id })
    }
}
