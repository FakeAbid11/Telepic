package com.telepix.ui.screens.organization

import android.net.Uri
import com.telepix.data.media.LocalMediaLookup
import com.telepix.data.media.NeighborDirection
import com.telepix.data.organization.DeleteRequest
import com.telepix.data.organization.LocalMediaDeleter
import com.telepix.data.organization.MediaOrganizationRepository
import com.telepix.domain.media.LocalMedia
import com.telepix.domain.media.MediaType
import com.telepix.navigation.OrganizationKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.Flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collection ViewModel maps favorite/archive/trash id-sets to media, and its undo / consented
 * delete actions drive the repository correctly. Fakes only — the permanent-delete *system consent*
 * is device-gated and is exercised here only through the [LocalMediaDeleter] seam.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrganizationViewModelTest {

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun media(id: Long) = LocalMedia(
        id = id, contentUri = Uri.parse("content://m/$id"), type = MediaType.PHOTO, mimeType = "image/jpeg",
        displayName = "f$id", dateMillis = 1L, durationMillis = null, width = 1, height = 1, sizeBytes = 1L,
        bucketId = null, bucketName = null, relativePath = null,
    )

    private class FakeRepo : MediaOrganizationRepository {
        val favorites = MutableStateFlow<Set<Long>>(emptySet())
        val archived = MutableStateFlow<Set<Long>>(emptySet())
        val trashed = MutableStateFlow<Set<Long>>(emptySet())
        val removed = mutableListOf<Long>()
        val deletedFromStore = mutableListOf<Long>()
        val unfavorited = mutableListOf<Long>()
        val unarchived = mutableListOf<Long>()
        val restored = mutableListOf<Long>()

        override val favoriteIds: Flow<Set<Long>> = favorites.asStateFlow()
        override val archivedIds: Flow<Set<Long>> = archived.asStateFlow()
        override val trashedIds: Flow<Set<Long>> = trashed.asStateFlow()
        override suspend fun hiddenIds(): Set<Long> = archived.value + trashed.value
        override suspend fun isFavorite(id: Long) = id in favorites.value
        override suspend fun isArchived(id: Long) = id in archived.value
        override suspend fun isTrashed(id: Long) = id in trashed.value
        override suspend fun setFavorite(id: Long, favorite: Boolean) { favorites.value = if (favorite) favorites.value + id else favorites.value - id }
        override suspend fun setArchived(id: Long, archived: Boolean) { this.archived.value = if (archived) this.archived.value + id else this.archived.value - id }
        override suspend fun moveToTrash(id: Long) { trashed.value = trashed.value + id }
        override suspend fun restoreFromTrash(id: Long) { restored += id; trashed.value = trashed.value - id }
        override suspend fun markDeletedFromStore(id: Long) { deletedFromStore += id }
        override suspend fun remove(id: Long) { removed += id }
    }

    private class FakeLookup : LocalMediaLookup {
        val items = mutableMapOf<Long, LocalMedia>()
        override suspend fun byId(id: Long): LocalMedia? = items[id]
        override suspend fun neighborId(id: Long, direction: NeighborDirection): Long? = null
        override suspend fun byIdList(ids: Collection<Long>): List<LocalMedia> = ids.mapNotNull { items[it } }
    }

    private class FakeDeleter(private val outcome: DeleteRequest) : LocalMediaDeleter {
        var requested: LocalMedia? = null
        override suspend fun requestDelete(media: LocalMedia): DeleteRequest { requested = media; return outcome }
    }

    @Test
    fun `favorites collection resolves the favorited ids to media`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo().apply { favorites.value = setOf(1L, 2L) }
        val lookup = FakeLookup().apply { items[1L] = media(1); items[2L] = media(2) }
        val vm = OrganizationViewModel(OrganizationKind.FAVORITES, repo, lookup, FakeDeleter(DeleteRequest.Failed))

        val items = vm.items.first { it.isNotEmpty() }
        assertEquals(setOf(1L, 2L), items.map { it.id }.toSet())
    }

    @Test
    fun `undo removes from favorites`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo().apply { favorites.value = setOf(5L) }
        val vm = OrganizationViewModel(OrganizationKind.FAVORITES, repo, FakeLookup(), FakeDeleter(DeleteRequest.Failed))
        vm.undo(5L)
        runCurrent()
        assertTrue(repo.removed.isEmpty()) // favorites undo is setFavorite(false), not remove
        assertEquals(emptySet<Long>(), repo.favorites.value)
    }

    @Test
    fun `archive undo unarchives`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo().apply { archived.value = setOf(3L) }
        val vm = OrganizationViewModel(OrganizationKind.ARCHIVE, repo, FakeLookup(), FakeDeleter(DeleteRequest.Failed))
        vm.undo(3L)
        runCurrent()
        assertEquals(emptySet<Long>(), repo.archived.value)
    }

    @Test
    fun `trash undo restores the item`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo().apply { trashed.value = setOf(4L) }
        val vm = OrganizationViewModel(OrganizationKind.TRASH, repo, FakeLookup(), FakeDeleter(DeleteRequest.Failed))
        vm.undo(4L)
        runCurrent()
        assertTrue(repo.restored.contains(4L))
    }

    @Test
    fun `a direct delete outcome forgets the item and records store deletion`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo()
        val deleter = FakeDeleter(DeleteRequest.Deleted)
        val vm = OrganizationViewModel(OrganizationKind.TRASH, repo, FakeLookup(), deleter)
        vm.onDeleteConfirmed(9L)
        runCurrent()
        assertTrue(repo.deletedFromStore.contains(9L))
        assertTrue(repo.removed.contains(9L))
    }

    @Test
    fun `requestDeleteForever delegates to the deleter seam`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val deleter = FakeDeleter(DeleteRequest.Failed)
        val vm = OrganizationViewModel(OrganizationKind.TRASH, FakeRepo(), FakeLookup(), deleter)
        val outcome = vm.requestDeleteForever(media(7))
        assertEquals(DeleteRequest.Failed, outcome)
        assertEquals(7L, deleter.requested?.id)
    }
}
