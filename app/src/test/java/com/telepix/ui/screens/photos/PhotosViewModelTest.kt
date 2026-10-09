package com.telepix.ui.screens.photos

import androidx.paging.PagingData
import android.net.Uri
import com.telepix.data.backup.BackupCoordinator
import com.telepix.data.backup.BackupRepository
import com.telepix.data.backup.BackupStatusRepository
import com.telepix.data.media.LocalMediaRepository
import com.telepix.data.media.MediaChangeWatcher
import com.telepix.domain.backup.BackupItem
import com.telepix.domain.backup.BackupQueueStats
import com.telepix.domain.backup.MediaBackupVisualState
import com.telepix.domain.media.LocalMedia
import com.telepix.domain.media.MediaType
import com.telepix.domain.media.PhotosItem
import com.telepix.permissions.MediaPermissionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the Photos ViewModel's permission state, refresh delegation, and lifecycle-safe change
 * watcher wiring — with a fake repository and watcher (no MediaStore).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotosViewModelTest {

    private class FakeRepository : LocalMediaRepository {
        var refreshCount = 0
        override val media: Flow<PagingData<PhotosItem>> = flowOf(PagingData.empty())
        override fun refresh() {
            refreshCount++
        }
    }

    private class FakeWatcher : MediaChangeWatcher {
        var started = false
        var stopped = false
        var callback: (() -> Unit)? = null
        override fun start(callback: () -> Unit) {
            started = true
            this.callback = callback
        }

        override fun stop() {
            stopped = true
        }
    }

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `starts the change watcher and forwards changes to the repository`() = runTest {
        val repository = FakeRepository()
        val watcher = FakeWatcher()
        PhotosViewModel(repository, watcher)

        assertTrue(watcher.started)
        watcher.callback?.invoke()
        assertEquals(1, repository.refreshCount)
    }

    @Test
    fun `updates the permission state`() = runTest {
        val viewModel = PhotosViewModel(FakeRepository(), FakeWatcher())
        viewModel.updatePermission(MediaPermissionState.Granted)
        assertEquals(MediaPermissionState.Granted, viewModel.permissionState.value)
    }

    @Test
    fun `refresh delegates to the repository`() = runTest {
        val repository = FakeRepository()
        PhotosViewModel(repository, FakeWatcher()).refresh()
        assertEquals(1, repository.refreshCount)
    }

    @Test
    fun `stops the watcher when cleared`() = runTest {
        val watcher = FakeWatcher()
        val viewModel = PhotosViewModel(FakeRepository(), watcher)
        // onCleared is invoked by the framework; call it reflectively for the test.
        androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
            .apply { isAccessible = true }
            .invoke(viewModel)
        assertTrue(watcher.stopped)
    }

    @Test
    fun `exposes backup status keyed by the numeric media id`() = runTest {
        val statusRepository = object : BackupStatusRepository {
            override val visualStates: Flow<Map<String, MediaBackupVisualState>> =
                flowOf(mapOf("7" to MediaBackupVisualState.BACKED_UP, "not-a-number" to MediaBackupVisualState.QUEUED))
        }
        val viewModel = PhotosViewModel(FakeRepository(), FakeWatcher(), statusRepository)
        // WhileSubscribed state needs a subscriber to begin; collect in the test scope.
        val job = launch { viewModel.backupStates.collect { } }
        runCurrent()
        // Non-numeric keys are dropped; the id stays the Long navigation identity.
        assertEquals(mapOf(7L to MediaBackupVisualState.BACKED_UP), viewModel.backupStates.value)
        job.cancel()
    }

    @Test
    fun `backup status is empty when no repository is wired`() = runTest {
        val viewModel = PhotosViewModel(FakeRepository(), FakeWatcher())
        val job = launch { viewModel.backupStates.collect { } }
        runCurrent()
        assertEquals(emptyMap<Long, MediaBackupVisualState>(), viewModel.backupStates.value)
        job.cancel()
    }

    // --- Multi-selection + bulk backup (Feature 1) -------------------------------------------

    private class FakeBackupRepository : BackupRepository {
        override fun observeQueue(): Flow<List<BackupItem>> = flowOf(emptyList())
        override fun observeStats(): Flow<BackupQueueStats> = flowOf(BackupQueueStats())
        override suspend fun enqueue(media: LocalMedia) = true
        override suspend fun enqueue(media: LocalMedia, contentHash: String, contentSizeBytes: Long) = true
        override suspend fun enqueueAll(media: List<LocalMedia>) = media.size
        override suspend fun retry(itemId: Long) = Unit
        override suspend fun cancel(itemId: Long) = Unit
        override suspend fun recoverInterruptedWork() = Unit
        override suspend fun processPendingWork(maxItems: Int) = com.telepix.data.backup.BackupProcessSummary()
    }

    /** Records what bulk backup hands to the real engine (which owns recognition/dedup/scheduling). */
    private class RecordingCoordinator : BackupCoordinator {
        override val repository: BackupRepository = FakeBackupRepository()
        val bulk = mutableListOf<List<LocalMedia>>()
        override suspend fun backup(media: LocalMedia): com.telepix.data.backup.BulkBackupSummary {
            bulk += listOf(media); return com.telepix.data.backup.BulkBackupSummary(1, 0)
        }
        override suspend fun backupAll(media: List<LocalMedia>): com.telepix.data.backup.BulkBackupSummary {
            bulk += media; return com.telepix.data.backup.BulkBackupSummary(media.size, 0)
        }
        override suspend fun retry(itemId: Long) = Unit
        override suspend fun cancel(itemId: Long) = Unit
        override suspend fun syncFromPreference() = Unit
        override suspend fun startPendingBackup() = Unit
    }

    private fun photo(id: Long) = LocalMedia(
        id = id, contentUri = Uri.parse("content://media/$id"), type = MediaType.PHOTO,
        mimeType = "image/jpeg", displayName = "p$id.jpg", dateMillis = 1L, durationMillis = null,
        width = 10, height = 10, sizeBytes = 100L, bucketId = 1L, bucketName = "Camera", relativePath = "DCIM/",
    )

    @Test
    fun `long-press begins selection and selects that item`() = runTest {
        val vm = PhotosViewModel(FakeRepository(), FakeWatcher(), backupCoordinator = RecordingCoordinator())
        assertTrue(vm.selectedItems.value.isEmpty())
        vm.beginSelection(photo(1))
        assertEquals(setOf(1L), vm.selectedItems.value.keys)
    }

    @Test
    fun `tapping toggles selection by stable id, independent of position`() = runTest {
        val vm = PhotosViewModel(FakeRepository(), FakeWatcher(), backupCoordinator = RecordingCoordinator())
        vm.beginSelection(photo(1))
        vm.toggleSelection(photo(2))
        assertEquals(setOf(1L, 2L), vm.selectedItems.value.keys)
        // Re-tapping an already-selected id removes only it.
        vm.toggleSelection(photo(1))
        assertEquals(setOf(2L), vm.selectedItems.value.keys)
        vm.toggleSelection(photo(2))
        assertTrue(vm.selectedItems.value.isEmpty())
    }

    @Test
    fun `back up selected hands the chosen items to the engine then clears selection`() = runTest {
        val coordinator = RecordingCoordinator()
        val vm = PhotosViewModel(FakeRepository(), FakeWatcher(), backupCoordinator = coordinator)
        vm.beginSelection(photo(1))
        vm.toggleSelection(photo(2))
        vm.backupSelected()
        runCurrent()

        assertEquals(1, coordinator.bulk.size)
        assertEquals(setOf(1L, 2L), coordinator.bulk.first().map { it.id }.toSet())
        // Selection is cleared and a queued-count is surfaced for feedback.
        assertTrue(vm.selectedItems.value.isEmpty())
        assertEquals(2, vm.selectionMessage.value?.queued)
    }

    @Test
    fun `back up selected with no coordinator wired is a harmless no-op`() = runTest {
        val vm = PhotosViewModel(FakeRepository(), FakeWatcher())
        vm.beginSelection(photo(1))
        vm.backupSelected()
        runCurrent()
        // Still selected (nothing enqueued), no crash, no message.
        assertEquals(setOf(1L), vm.selectedItems.value.keys)
        assertEquals(null, vm.selectionMessage.value)
    }
}
