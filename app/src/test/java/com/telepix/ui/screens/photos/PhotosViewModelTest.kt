package com.telepix.ui.screens.photos

import androidx.paging.PagingData
import com.telepix.data.backup.BackupStatusRepository
import com.telepix.data.media.LocalMediaRepository
import com.telepix.data.media.MediaChangeWatcher
import com.telepix.domain.backup.MediaBackupVisualState
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
    fun `backup status is empty when no repository is wired (offline / no queue)`() = runTest {
        val viewModel = PhotosViewModel(FakeRepository(), FakeWatcher())
        val job = launch { viewModel.backupStates.collect { } }
        runCurrent()
        assertEquals(emptyMap<Long, MediaBackupVisualState>(), viewModel.backupStates.value)
        job.cancel()
    }
