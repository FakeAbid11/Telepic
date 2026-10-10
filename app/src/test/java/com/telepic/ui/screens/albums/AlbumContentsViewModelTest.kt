package com.telepic.ui.screens.albums

import android.net.Uri
import androidx.paging.PagingData
import com.telepic.data.backup.BackupCoordinator
import com.telepic.data.backup.BackupRepository
import com.telepic.data.backup.BackupStatusRepository
import com.telepic.data.backup.BulkBackupSummary
import com.telepic.data.media.AlbumRepository
import com.telepic.domain.backup.BackupItem
import com.telepic.domain.backup.BackupQueueStats
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.media.Album
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import com.telepic.domain.media.PhotosItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
        override suspend fun albums(): List<Album> = emptyList()
        override fun mediaInBucket(bucketId: Long): Flow<PagingData<PhotosItem>> = flowOf(PagingData.empty())
    }

    private class EmptyStatusRepository : BackupStatusRepository {
        override val visualStates: Flow<Map<String, MediaBackupVisualState>> = flowOf(emptyMap())
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
    private fun viewModel(backupCoordinator: BackupCoordinator? = null) = AlbumContentsViewModel(
        bucketId = 3L,
        title = "Camera",
        albumRepository = FakeAlbumRepository(),
        backupStatusRepository = EmptyStatusRepository(),
        backupCoordinator = backupCoordinator,
    )

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
        assertEquals(2, vm.selectionMessage.value?.queued)
    }

    @Test
    fun `back up selected with no coordinator wired is a harmless no-op`() = runTest {
        val vm = viewModel()
        vm.beginSelection(photo(1))
        vm.backupSelected()
        runCurrent()
        assertEquals(setOf(1L), vm.selectedItems.value.keys)
        assertEquals(null, vm.selectionMessage.value)
    }
}
