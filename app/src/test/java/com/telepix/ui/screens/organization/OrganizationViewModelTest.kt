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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The collection ViewModel maps favorite/archive/trash id-sets to media, and its undo / consented
 * delete actions drive the repository correctly. Fakes only — the permanent-delete system consent is
 * device-gated and is exercised here only through the [LocalMediaDeleter] seam.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OrganizationViewModelTest {

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun media(id: Long) = LocalMedia(
        id = id, contentUri = Uri.parse("content://m/$id"), type = MediaType.PHOTO, mimeType = "image/jpeg",
        displayName = "f$id", dateMillis = 1L, durationMillis = null, width = 1, height = 1, sizeBytes = 1L,
        bucketId = null, bucketName = null, relativePath = null,
    )

    private class FakeRepo : MediaOrganizationRepository {
        val favs = MutableStateFlow<Set<Long>>(emptySet())
        val arch = MutableStateFlow<Set<Long>>(emptySet())
        val trash = MutableStateFlow<Set<Long>>(emptySet())
        val removed = mutableListOf<Long>()
        val deletedFromStore = mutableListOf<Long>()
        val restored = mutableListOf<Long>()

        override val favoriteIds: Flow<Set<Long>> = favs.asStateFlow()
        override val archivedIds: Flow<Set<Long>> = arch.asStateFlow()
        override val trashedIds: Flow<Set<Long>> = trash.asStateFlow()
        override suspend fun hiddenIds(): Set<Long> = arch.value + trash.value
        override suspend fun isFavorite(id: Long) = id in favs.value
        override suspend fun isArchived(id: Long) = id in arch.value
        override suspend fun isTrashed(id: Long) = id in trash.value

        override suspend fun setFavorite(id: Long, favorite: Boolean) {
            favs.value = if (favorite) favs.value + id else favs.value - id
        }

        override suspend fun setArchived(id: Long, archived: Boolean) {
            arch.value = if (archived) arch.value + id else arch.value - id
        }

        override suspend fun moveToTrash(id: Long) { trash.value = trash.value + id }

        override suspend fun restoreFromTrash(id: Long) {
            restored += id
            trash.value = trash.value - id
        }

        override suspend fun markDeletedFromStore(id: Long) { deletedFromStore += id }
        override suspend fun remove(id: Long) { removed += id }
    }

    private class FakeLookup : LocalMediaLookup {
        val store = mutableMapOf<Long, LocalMedia>()
        override suspend fun byId(id: Long): LocalMedia? = store[id]
        override suspend fun neighborId(id: Long, direction: NeighborDirection, bucketId: Long?): Long? = null
        override suspend fun byIdList(ids: Collection<Long>): List<LocalMedia> =
            ids.toList().mapNotNull { store[it] }
    }

    private class FakeDeleter(private val outcome: DeleteRequest) : LocalMediaDeleter {
        var requested: LocalMedia? = null
        override suspend fun requestDelete(media: LocalMedia): DeleteRequest {
            requested = media
            return outcome
        }
    }

    @Test
    fun `favorites collection resolves the favorited ids to media`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo().apply { favs.value = setOf(1L, 2L) }
        val lookup = FakeLookup().apply {
            store[1L] = media(1L)
            store[2L] = media(2L)
        }
        val vm = OrganizationViewModel(OrganizationKind.FAVORITES, repo, lookup, FakeDeleter(DeleteRequest.Failed))
        val items = vm.items.first { it.isNotEmpty() }
        assertEquals(setOf(1L, 2L), items.map { it.id }.toSet())
    }

    @Test
    fun `undo removes from favorites`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo().apply { favs.value = setOf(5L) }
        val vm = OrganizationViewModel(OrganizationKind.FAVORITES, repo, FakeLookup(), FakeDeleter(DeleteRequest.Failed))
        vm.undo(5L)
        runCurrent()
        assertTrue(repo.removed.isEmpty())
        assertEquals(emptySet<Long>(), repo.favs.value)
    }

    @Test
    fun `archive undo unarchives`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo().apply { arch.value = setOf(3L) }
        val vm = OrganizationViewModel(OrganizationKind.ARCHIVE, repo, FakeLookup(), FakeDeleter(DeleteRequest.Failed))
        vm.undo(3L)
        runCurrent()
        assertEquals(emptySet<Long>(), repo.arch.value)
    }

    @Test
    fun `trash undo restores the item`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo().apply { trash.value = setOf(4L) }
        val vm = OrganizationViewModel(OrganizationKind.TRASH, repo, FakeLookup(), FakeDeleter(DeleteRequest.Failed))
        vm.undo(4L)
        runCurrent()
        assertTrue(repo.restored.contains(4L))
    }

    @Test
    fun `a direct delete outcome forgets the item and records store deletion`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo()
        val vm = OrganizationViewModel(OrganizationKind.TRASH, repo, FakeLookup(), FakeDeleter(DeleteRequest.Deleted))
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
        val outcome = vm.requestDeleteForever(media(7L))
        assertEquals(DeleteRequest.Failed, outcome)
        assertEquals(7L, deleter.requested?.id)
    }
}
