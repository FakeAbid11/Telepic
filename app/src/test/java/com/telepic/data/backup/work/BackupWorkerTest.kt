package com.telepic.data.backup.work

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.telepic.data.backup.BackupProcessSummary
import com.telepic.data.backup.BackupRepository
import com.telepic.domain.backup.BackupItem
import com.telepic.domain.backup.BackupQueueStats
import com.telepic.domain.media.LocalMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives the real [BackupWorker] against a scripted fake repository (no Telegram, no device). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupWorkerTest {

    private class FakeRepo(private val summaries: MutableList<BackupProcessSummary>) : BackupRepository {
        var recovered = false
        var calls = 0
        override fun observeQueue(): Flow<List<BackupItem>> = flowOf(emptyList())
        override fun observeStats(): Flow<BackupQueueStats> = flowOf(BackupQueueStats())
        override suspend fun enqueue(media: LocalMedia) = true
        override suspend fun enqueueAll(media: List<LocalMedia>) = 0
        override suspend fun retry(itemId: Long) = Unit
        override suspend fun cancel(itemId: Long) = Unit
        override suspend fun recoverInterruptedWork() { recovered = true }
        override suspend fun processPendingWork(maxItems: Int): BackupProcessSummary {
            calls++
            return if (summaries.isNotEmpty()) summaries.removeAt(0) else BackupProcessSummary()
        }
    }

    @After
    fun tearDown() {
        BackupWorkerDependencies.repository = null
    }

    private fun worker(repo: BackupRepository): BackupWorker {
        BackupWorkerDependencies.repository = repo
        return TestListenableWorkerBuilder<BackupWorker>(ApplicationProvider.getApplicationContext()).build()
            as BackupWorker
    }

    @Test
    fun `worker recovers interrupted work then succeeds when uploads complete`() = runBlocking {
        val repo = FakeRepo(
            mutableListOf(
                BackupProcessSummary(uploaded = 2, processed = 2),
                BackupProcessSummary(processed = 0),
            ),
        )
        val result = worker(repo).doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(repo.recovered)
    }

    @Test
    fun `worker requests retry when the only progress was waiting work`() = runBlocking {
        val repo = FakeRepo(
            mutableListOf(
                BackupProcessSummary(waiting = 1, processed = 1),
                BackupProcessSummary(processed = 0),
            ),
        )
        val result = worker(repo).doWork()
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `worker succeeds when the queue has no actionable work`() = runBlocking {
        val repo = FakeRepo(mutableListOf())
        assertEquals(ListenableWorker.Result.success(), worker(repo).doWork())
    }
}
