package com.telepic.ui.screens.albums

import android.net.Uri
import androidx.paging.PagingData
import com.telepic.data.backup.BackupCoordinator
import com.telepic.data.backup.BackupRepository
import com.telepic.data.backup.BackupStatusRepository
import com.telepic.data.backup.BulkBackupSummary
import com.telepic.data.media.AlbumRepository
import com.telepic.data.media.CustomAlbumRepository
import com.telepic.data.organization.MediaOrganizationRepository
import com.telepic.domain.backup.BackupItem
import com.telepic.domain.backup.BackupQueueStats
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.media.Album
import com.telepic.domain.media.CustomAlbum
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import com.telepic.domain.media.PhotosItem
import com.telepic.navigation.AlbumSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Album-contents selection must behave exactly like the Photos timeline: stable-id keyed selection,
 * bulk backup handed to the shared engine (which owns recognition/dedup/scheduling), then cleared
 * with an honest queued count — and a harmless no-op when no coordinator is wired.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlbumContentsViewModelTest {

    private class FakeAlbumRepository : AlbumRepository {
        /** Recorded so a test can prove a hidden-state change re-reads the bucket. */
        var refreshCalls = 0
        override suspend fun albums(): List<Album> = emptyList()
        override fun mediaInBucket(bucketId: Long): Flow<PagingData<PhotosItem>> = flowOf(PagingData.empty())
        override fun refresh() { refreshCalls++ }
    }

    private class EmptyStatusRepository : BackupStatusRepository {
        override val visualStates: Flow<Map<String, MediaBackupVisualState>> = flowOf(emptyMap())
    }

    /** Only membership removal matters here; the rest of a user-created album is inert. */
    private class FakeCustomAlbums : CustomAlbumRepository {
        val removed = mutableListOf<Long>()
        override val albums: Flow<List<CustomAlbum>> = flowOf(emptyList())
        override suspend fun create(name: String): Long? = null
        override suspend fun rename(albumId: Long, name: String) = Unit
        override suspend fun delete(albumId: Long) = Unit
        override suspend fun addMedia(albumId: Long, mediaIds: List<Long>): Int = 0
        override suspend fun removeMedia(albumId: Long, localMediaId: Long) {
            removed += localMediaId
        }

        override fun observeName(albumId: Long): Flow<String?> = flowOf("Trip")
        override fun observeMediaIds(albumId: Long): Flow<List<Long>> = flowOf(emptyList())
        override suspend fun mediaIds(albumId: Long): List<Long> = emptyList()
        override fun mediaInAlbum(albumId: Long): Flow<PagingData<PhotosItem>> = flowOf(PagingData.empty())
        override fun refresh() = Unit
    }

    /** Only the two hidden-state flows the album watches matter here; the rest are inert. */
    private class FakeOrg(
        private val archived: MutableStateFlow<Set<Long>> = MutableStateFlow(emptySet()),
        private val trashed: MutableStateFlow<Set<Long>> = MutableStateFlow(emptySet()),
    ) : MediaOrganizationRepository {
        override val favoriteIds: Flow<Set<Long>> = flowOf(emptySet())
        override val archivedIds: Flow<Set<Long>> = archived
        override val trashedIds: Flow<Set<Long>> = trashed
        override suspend fun hiddenIds(): Set<Long> = archived.value + trashed.value
        override suspend fun isFavorite(id: Long) = false
        override suspend fun isArchived(id: Long) = id in archived.value
        override suspend fun isTrashed(id: Long) = id in trashed.value
        override suspend fun setFavorite(id: Long, favorite: Boolean) = Unit
        override suspend fun setArchived(id: Long, archived: Boolean) = Unit
        override suspend fun moveToTrash(id: Long) = Unit
        override suspend fun restoreFromTrash(id: Long) = Unit
        override suspend fun markDeletedFromStore(id: Long) = Unit
        override suspend fun remove(id: Long) = Unit
    }

    private class FakeBackupRepository : BackupRepository {
        override fun observeQueue(): Flow<List<BackupItem>> = flowOf(emptyList())
        override fun observeStats(): Flow<BackupQueueStats> = flowOf(BackupQueueStats())
        override suspend fun enqueue(media: LocalMedia) = true
        override suspend fun enqueue(media: LocalMedia, contentHash: String, contentSizeBytes: Long) = true
        override suspend fun enqueueAll(media: List<LocalMedia>) = media.size
        override suspend fun retry(itemId: Long) = Unit
        override suspend fun cancel(itemId: Long) = Unit
        override suspend fun recoverInterruptedWork() = Unit
        override suspend fun processPendingWork(maxItems: Int) = com.telepic.data.backup.BackupProcessSummary()
    }

    /** Records what bulk backup hands to the real engine (which owns recognition/dedup/scheduling). */
    private class RecordingCoordinator : BackupCoordinator {
        override val repository: BackupRepository = FakeBackupRepository()
        val bulk = mutableListOf<List<LocalMedia>>()
        override suspend fun backup(media: LocalMedia): BulkBackupSummary {
            bulk += listOf(media); return BulkBackupSummary(1, 0)
        }
        override suspend fun backupAll(media: List<LocalMedia>): BulkBackupSummary {
            bulk += media; return BulkBackupSummary(media.size, 0)
        }
        override suspend fun retry(itemId: Long) = Unit
        override suspend fun cancel(itemId: Long) = Unit
        override suspend fun syncFromPreference() = Unit
        override suspend fun startPendingBackup() = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun photo(id: Long) = LocalMedia(
        id = id, contentUri = Uri.parse("content://media/$id"), type = MediaType.PHOTO,
        mimeType = "image/jpeg", displayName = "p$id.jpg", dateMillis = 1L, durationMillis = null,
        width = 10, height = 10, sizeBytes = 100L, bucketId = 3L, bucketName = "Camera", relativePath = "DCIM/",
    )

    /** The screen builds the VM from the route's (bucketId, title) — no fabricated Album record. */
    private fun viewModel(
        backupCoordinator: BackupCoordinator? = null,
        albumRepository: AlbumRepository = FakeAlbumRepository(),
        organizationRepository: MediaOrganizationRepository? = null,
    ) = AlbumContentsViewModel(
        source = AlbumSource.Bucket(3L, "Camera"),
        albumRepository = albumRepository,
        backupStatusRepository = EmptyStatusRepository(),
        backupCoordinator = backupCoordinator,
        organizationRepository = organizationRepository,
    )

    /** A user-created album — the only kind whose membership the user can change. */
    private fun customViewModel(customAlbums: CustomAlbumRepository) = AlbumContentsViewModel(
        source = AlbumSource.Custom(7L, title = "Trip"),
        albumRepository = FakeAlbumRepository(),
        customAlbumRepository = customAlbums,
        backupStatusRepository = EmptyStatusRepository(),
    )

    @Test
    fun `removing from an album reports how many were removed`() = runTest {
        val customAlbums = FakeCustomAlbums()
        val vm = customViewModel(customAlbums)
        vm.beginSelection(photo(1))
        vm.toggleSelection(photo(2))
        vm.toggleSelection(photo(3))
        vm.removeSelectedFromAlbum()
        runCurrent()

        assertEquals(setOf(1L, 2L, 3L), customAlbums.removed.toSet())
        // A removal leaves no trace on screen — the grid simply has fewer tiles — so without this the
        // user cannot tell a successful removal from a tap that did nothing.
        assertEquals(3, vm.removalReport.value?.removed)
        assertTrue(vm.selectedItems.value.isEmpty())
    }

    @Test
    fun `long-press begins selection and selects that item`() = runTest {
        val vm = viewModel(RecordingCoordinator())
        assertTrue(vm.selectedItems.value.isEmpty())
        vm.beginSelection(photo(1))
        assertEquals(setOf(1L), vm.selectedItems.value.keys)
    }

    @Test
    fun `tapping toggles selection by stable id, independent of position`() = runTest {
        val vm = viewModel(RecordingCoordinator())
        vm.beginSelection(photo(1))
        vm.toggleSelection(photo(2))
        assertEquals(setOf(1L, 2L), vm.selectedItems.value.keys)
        vm.toggleSelection(photo(1))
        assertEquals(setOf(2L), vm.selectedItems.value.keys)
        vm.toggleSelection(photo(2))
        assertTrue(vm.selectedItems.value.isEmpty())
    }

    @Test
    fun `back up selected hands the chosen items to the engine then clears selection`() = runTest {
        val coordinator = RecordingCoordinator()
        val vm = viewModel(coordinator)
        vm.beginSelection(photo(1))
        vm.toggleSelection(photo(2))
        vm.backupSelected()
        runCurrent()

        assertEquals(1, coordinator.bulk.size)
        assertEquals(setOf(1L, 2L), coordinator.bulk.first().map { it.id }.toSet())
        assertTrue(vm.selectedItems.value.isEmpty())
        assertEquals(2, vm.selectionReport.value?.summary?.queued)
    }

    @Test
    fun `back up selected with no coordinator wired is a harmless no-op`() = runTest {
        val vm = viewModel()
        vm.beginSelection(photo(1))
        vm.backupSelected()
        runCurrent()
        assertEquals(setOf(1L), vm.selectedItems.value.keys)
        assertEquals(null, vm.selectionReport.value)
    }

    @Test
    fun `archiving or trashing re-reads the album instead of leaving it stale`() = runTest {
        val archived = MutableStateFlow(emptySet<Long>())
        val albums = FakeAlbumRepository()
        viewModel(albumRepository = albums, organizationRepository = FakeOrg(archived = archived))
        runCurrent()
        val before = albums.refreshCalls

        // Archiving from the Viewer changes what this album should contain, and a `cachedIn` pager
        // never re-queries on its own — so the item stayed listed with a stale count, and this screen
        // has no other way to refresh. The Photos timeline updated immediately; the album did not.
        archived.value = setOf(1L)
        runCurrent()

        assertTrue("expected the album to be invalidated", albums.refreshCalls > before)
    }
}
