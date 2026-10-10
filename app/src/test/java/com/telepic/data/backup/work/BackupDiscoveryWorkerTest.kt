package com.telepic.data.backup.work

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.telepic.data.backup.BackupCoordinator
import com.telepic.data.backup.BackupProcessSummary
import com.telepic.data.backup.BackupRepository
import com.telepic.data.backup.BulkBackupSummary
import com.telepic.domain.backup.BackupItem
import com.telepic.domain.backup.BackupQueueStats
import com.telepic.domain.media.LocalMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real [BackupDiscoveryWorker] against a fake coordinator: the periodic pass runs the
 * preference-gated sync exactly once and never uploads itself — a transient sync failure is an
 * honest retry (next period), not a silent success.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupDiscoveryWorkerTest {

    private class RecordingCoordinator(private val failure: Exception? = null) : BackupCoordinator {
        var syncCalls = 0
        override val repository: BackupRepository = object : BackupRepository {
            override fun observeQueue(): Flow<List<BackupItem>> = flowOf(emptyList())
            override fun observeStats(): Flow<BackupQueueStats> = flowOf(BackupQueueStats())
            override suspend fun enqueue(media: LocalMedia) = true
            override suspend fun enqueue(media: LocalMedia, contentHash: String, contentSizeBytes: Long) = true
            override suspend fun enqueueAll(media: List<LocalMedia>) = media.size
            override suspend fun retry(itemId: Long) = Unit
            override suspend fun cancel(itemId: Long) = Unit
            override suspend fun recoverInterruptedWork() = Unit
            override suspend fun processPendingWork(maxItems: Int) = BackupProcessSummary()
        }
        override suspend fun backup(media: LocalMedia) = BulkBackupSummary.Empty
        override suspend fun backupAll(media: List<LocalMedia>) = BulkBackupSummary.Empty
        override suspend fun retry(itemId: Long) = Unit
        override suspend fun cancel(itemId: Long) = Unit
        override suspend fun syncFromPreference() {
            syncCalls++
            failure?.let { throw it }
        }
        override suspend fun startPendingBackup() = Unit
    }

    @After
    fun tearDown() {
        BackupWorkerDependencies.coordinator = null
    }

    /**
     * Deterministic construction via the production [TelepicWorkerFactory] seam — the process-global
     * BackupWorkerDependencies static is overwritten by the Robolectric-created TelepicApplication's
     * lazy container init, which races the per-test fake (see BackupWorkerTest for the full note).
     */
    private fun worker(coordinator: BackupCoordinator): BackupDiscoveryWorker {
        val factory = TelepicWorkerFactory(backupRepository = { error("unused") }, backupCoordinator = { coordinator })
        return TestListenableWorkerBuilder<BackupDiscoveryWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(factory)
            .build() as BackupDiscoveryWorker
    }

    @Test
    fun `periodic pass runs the preference-gated sync once and succeeds`() = runBlocking {
        val coordinator = RecordingCoordinator()
        val result = worker(coordinator).doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, coordinator.syncCalls)
    }

    @Test
    fun `a transient discovery failure asks WorkManager to retry, never claims success`() = runBlocking {
        val coordinator = RecordingCoordinator(failure = IllegalStateException("MediaStore unavailable"))
        val result = worker(coordinator).doWork()
        assertEquals(ListenableWorker.Result.retry(), result)
    }
}
