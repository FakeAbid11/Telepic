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
    private fun worker(repo: BackupRepository, scheduler: BackupWorkScheduler? = null): BackupWorker {
        val factory = TelepicWorkerFactory(
            backupRepository = { repo },
            backupCoordinator = { error("unused") },
            backupScheduler = { scheduler },
        )
        return TestListenableWorkerBuilder<BackupWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(factory)
            .build() as BackupWorker
    }

    /** Records how the worker hands the remainder of a long drain to a fresh run. */
    private class RecordingScheduler : BackupWorkScheduler {
        var continuations = 0
        override suspend fun schedule() = Unit
        override suspend fun schedulePeriodic() = Unit
        override suspend fun scheduleContinuation() { continuations++ }
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
    fun `a full batch is drained inside the same run instead of asking WorkManager to retry`() = runBlocking {
        // The regression this guards: a first backup longer than one batch used to be handed back to
        // WorkManager as Result.retry(), which re-runs the *same* WorkSpec — so the exponential
        // backoff grew between batches (10s, 20s, 40s, … capped at five hours) and a ten-thousand
        // item library needed about a hundred batches, i.e. roughly twenty days. The drain now
        // happens inside one run.
        val repo = FakeRepo(
            mutableListOf(
                BackupProcessSummary(uploaded = 100, processed = 100),
                BackupProcessSummary(uploaded = 40, processed = 40),
            ),
            stats = BackupQueueStats(queued = 40),
        )
        assertEquals(ListenableWorker.Result.success(), worker(repo).doWork())
        // Three passes, one run: the second batch picked up what the first one left, and the third
        // found nothing actionable — which is what ends the drain.
        assertEquals(3, repo.calls)
    }

    @Test
    fun `a full batch that drained the queue still reports success`() = runBlocking {
        val repo = FakeRepo(
            mutableListOf(BackupProcessSummary(uploaded = 100, processed = 100)),
            stats = BackupQueueStats(completed = 100),
        )
        assertEquals(ListenableWorker.Result.success(), worker(repo).doWork())
    }

    @Test
    fun `a waiting row ends the drain so its retry budget is not burned inside one run`() = runBlocking {
        // A row that is waiting for the network is still actionable, so the next batch would pick it
        // up again. Retrying it immediately would burn its whole retry budget inside one run — the
        // backoff is what is supposed to space those retries.
        val repo = FakeRepo(
            mutableListOf(
                BackupProcessSummary(waiting = 1, processed = 1),
                BackupProcessSummary(uploaded = 100, processed = 100),
            ),
        )
        val result = worker(repo).doWork()
        assertEquals(ListenableWorker.Result.retry(), result)
        // The second batch was never reached: the drain stopped at the waiting row.
        assertEquals(1, repo.calls)
    }

    @Test
    fun `a run that reaches its batch cap hands the rest to a fresh run`() = runBlocking {
        // The batch cap bounds one foreground notification's lifetime, and the remainder has to go to
        // a *fresh* run: `Result.retry()` re-runs the same WorkSpec, so the exponential backoff grew
        // between batches until a large library took about twenty days to back up.
        val repo = FakeRepo(MutableList(25) { BackupProcessSummary(uploaded = 100, processed = 100) })
        val scheduler = RecordingScheduler()
        val result = worker(repo, scheduler).doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("the remainder must go to a fresh run", 1, scheduler.continuations)
    }

    @Test
    fun `the notification channel is created once per run, independent of any notification`() = runBlocking {        // An empty queue means buildNotification is never called this run, so the channel can only
        // exist because runPass() creates it up front — the point of moving ensureChannel off the
        // per-progress-update path.
        val repo = FakeRepo(mutableListOf())
        assertEquals(ListenableWorker.Result.success(), worker(repo).doWork())

        val manager = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(android.app.NotificationManager::class.java)
        assertTrue("backup channel was not created", manager.getNotificationChannel("telepic_backup") != null)
    }
}
