package com.telepic.ui.screens.backup

import com.telepic.data.backup.BackupCoordinator
import com.telepic.data.backup.BackupProcessSummary
import com.telepic.data.backup.BackupRepository
import com.telepic.data.backup.BulkBackupSummary
import com.telepic.domain.backup.BackupItem
import com.telepic.domain.backup.BackupQueueStats
import com.telepic.domain.media.LocalMedia
import com.telepic.telegram.TelegramAuthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Backup Center reports a failed tap with a single [BackupMessage.ActionFailed] object, so two
 * failures in a row are indistinguishable by value. The transient bar keys its countdown on
 * `messageId`, so each emission must carry a fresh token or the second failure inherits the first
 * event's nearly-expired timer and flashes for no time at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupViewModelTest {

    private class FakeRepo : BackupRepository {
        override fun observeQueue(): Flow<List<BackupItem>> = flowOf(emptyList())
        override fun observeStats(): Flow<BackupQueueStats> = flowOf(BackupQueueStats())
        override suspend fun enqueue(media: LocalMedia) = true
        override suspend fun enqueueAll(media: List<LocalMedia>) = 0
        override suspend fun retry(itemId: Long) = Unit
        override suspend fun cancel(itemId: Long) = Unit
        override suspend fun recoverInterruptedWork() = Unit
        override suspend fun processPendingWork(maxItems: Int) = BackupProcessSummary()
    }

    /** Fails every action, which is the one path that reports [BackupMessage.ActionFailed]. */
    private class FailingCoordinator : BackupCoordinator {
        override val repository: BackupRepository = FakeRepo()
        override suspend fun backup(media: LocalMedia) = BulkBackupSummary(0, 0)
        override suspend fun backupAll(media: List<LocalMedia>) = BulkBackupSummary(0, 0)
        override suspend fun retry(itemId: Long) = Unit
        override suspend fun cancel(itemId: Long) = Unit
        override suspend fun syncFromPreference() = Unit
        override suspend fun startPendingBackup(): Unit = throw IllegalStateException("boom")
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): BackupViewModel {
        val auth: StateFlow<TelegramAuthState> =
            MutableStateFlow<TelegramAuthState>(TelegramAuthState.NotConnected).asStateFlow()
        return BackupViewModel(FailingCoordinator(), auth)
    }

    @Test
    fun `a repeated failure bumps the message id even though the message is identical`() = runTest {
        val vm = viewModel()

        vm.startBackup()
        val firstId = vm.messageId.value
        assertEquals(BackupMessage.ActionFailed, vm.message.value)

        vm.startBackup()
        assertTrue(vm.messageId.value > firstId)
        assertEquals(BackupMessage.ActionFailed, vm.message.value)
    }
}
