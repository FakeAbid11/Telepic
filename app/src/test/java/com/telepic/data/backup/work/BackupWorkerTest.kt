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

    private class FakeRepo(
        private val summaries: MutableList<BackupProcessSummary>,
        /** What the queue looks like after the batch — the worker re-reads it to decide. */
        private val stats: BackupQueueStats = BackupQueueStats(),
    ) : BackupRepository {
        var recovered = false
        var calls = 0
        override fun observeQueue(): Flow<List<BackupItem>> = flowOf(emptyList())
        override fun observeStats(): Flow<BackupQueueStats> = flowOf(stats)
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

    /**
     * Built through the production [TelepicWorkerFactory] seam — the same route the app uses, since
     * the manifest removes WorkManager's default initializer and the reflective constructor now fails
     * loudly rather than reading a process-global. There is no global left to reset between tests.
     */
    private fun worker(repo: BackupRepository): BackupWorker {
        val factory = TelepicWorkerFactory(backupRepository = { repo }, backupCoordinator = { error("unused") })
        return TestListenableWorkerBuilder<BackupWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(factory)
            .build() as BackupWorker
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

    @Test
    fun `a batch parked on a missing session ends the run instead of retrying forever`() = runBlocking {
        val repo = FakeRepo(
            mutableListOf(BackupProcessSummary(processed = 3, waitingForAuth = 3)),
            // The rows stay "active" in the queue — which is precisely what made the worker ask
            // WorkManager for another run every backoff period, indefinitely, each time raising a
            // foreground-service notification for a condition only signing in can fix.
            stats = BackupQueueStats(queued = 3),
        )
        assertEquals(ListenableWorker.Result.success(), worker(repo).doWork())
    }

    @Test
    fun `only a purely auth-blocked batch stops the loop - a network wait still retries`() = runBlocking {
        val repo = FakeRepo(
            mutableListOf(BackupProcessSummary(processed = 3, waiting = 1, waitingForAuth = 2)),
        )
        assertEquals(ListenableWorker.Result.retry(), worker(repo).doWork())
    }

    @Test
    fun `a full batch with a longer queue asks for another run instead of reporting success`() = runBlocking {
        val repo = FakeRepo(
            mutableListOf(BackupProcessSummary(uploaded = 100, processed = 100)),
            stats = BackupQueueStats(queued = 40),
        )
        // The regression this guards: a first backup longer than one batch used to report success
        // here and stop for good, because nothing else re-schedules a still-queued library.
        assertEquals(ListenableWorker.Result.retry(), worker(repo).doWork())
    }

    @Test
    fun `a full batch that drained the queue still reports success`() = runBlocking {
        val repo = FakeRepo(
            mutableListOf(BackupProcessSummary(uploaded = 100, processed = 100)),
            stats = BackupQueueStats(completed = 100),
        )
        assertEquals(ListenableWorker.Result.success(), worker(repo).doWork())
    }
}
