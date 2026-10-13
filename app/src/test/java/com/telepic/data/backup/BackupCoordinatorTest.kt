package com.telepic.data.backup

import android.net.Uri
import com.telepic.data.backup.work.BackupWorkScheduler
import com.telepic.domain.backup.BackupItem
import com.telepic.domain.backup.BackupQueueStats
import com.telepic.domain.backup.BackupRecognitionResult
import com.telepic.domain.backup.RemoteMediaIdentity
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import com.telepic.onboarding.BackupPreference
import com.telepic.onboarding.OnboardingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The coordinator recognizes before enqueuing and honors the Phase 2 backup preference. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupCoordinatorTest {

    private class RecordingRepo : BackupRepository {
        val enqueued = mutableListOf<LocalMedia>()
        val enqueuedWithHash = mutableListOf<Pair<LocalMedia, String>>()
        override fun observeQueue(): Flow<List<BackupItem>> = flowOf(emptyList())
        override fun observeStats(): Flow<BackupQueueStats> = flowOf(BackupQueueStats())
        override suspend fun enqueue(media: LocalMedia): Boolean { enqueued += media; return true }
        override suspend fun enqueue(media: LocalMedia, contentHash: String, contentSizeBytes: Long): Boolean {
            enqueuedWithHash += media to contentHash
            return true
        }
        override suspend fun enqueueAll(media: List<LocalMedia>): Int { enqueued += media; return media.size }
        override suspend fun retry(itemId: Long) = Unit
        override suspend fun cancel(itemId: Long) = Unit
        override suspend fun recoverInterruptedWork() = Unit
        override suspend fun processPendingWork(maxItems: Int) = BackupProcessSummary()
    }

    private class FakeScheduler : BackupWorkScheduler {
        var scheduled = 0
        var periodicScheduled = 0
        override suspend fun schedule() { scheduled++ }
        override suspend fun schedulePeriodic() { periodicScheduled++ }
    }

    private class FakeOnboarding(
        private val preference: BackupPreference?,
        private val buckets: Set<Long> = emptySet(),
    ) : OnboardingRepository {
        override val isCompleted: Flow<Boolean> = flowOf(true)
        override val backupPreference: Flow<BackupPreference?> = flowOf(preference)
        override val backupBucketIds: Flow<Set<Long>> = flowOf(buckets)
        override suspend fun setCompleted(completed: Boolean) = Unit
        override suspend fun setBackupPreference(preference: BackupPreference?) = Unit
        override suspend fun setBackupBucketIds(ids: Set<Long>) = Unit
    }

    private class FakeLoader(private val pages: List<List<LocalMedia>>, private val pageSize: Int) :
        com.telepic.data.media.MediaPageLoader {
        override suspend fun load(offset: Int, limit: Int): List<LocalMedia> =
            pages.getOrElse(offset / pageSize) { emptyList() }
    }

    /** Recognition that always says "needs backup" with a deterministic hash. */
    private class NeedsBackupRecognition : BackupRecognitionRepository {
        var calls = 0
        override suspend fun recognize(media: LocalMedia): BackupRecognitionResult {
            calls++
            return BackupRecognitionResult.NeedsBackup("hash-${media.id}", media.sizeBytes)
        }
        override suspend fun markRecognized(media: LocalMedia, remote: RemoteMediaIdentity, hash: String, sizeBytes: Long) = Unit
    }

    private class FixedRecognition(private val result: (LocalMedia) -> BackupRecognitionResult) : BackupRecognitionRepository {
        override suspend fun recognize(media: LocalMedia) = result(media)
        override suspend fun markRecognized(media: LocalMedia, remote: RemoteMediaIdentity, hash: String, sizeBytes: Long) = Unit
    }

    /** Serves [pages] and then fails, like a provider that stops answering part-way through a scan. */
    private class FailingLoader(private val pages: List<List<LocalMedia>>, private val pageSize: Int) :
        com.telepic.data.media.MediaPageLoader {
        override suspend fun load(offset: Int, limit: Int): List<LocalMedia> {
            val index = offset / pageSize
            if (index >= pages.size) throw SecurityException("media access was revoked mid-scan")
            return pages[index]
        }
    }

    /** Throws for one id, standing in for a recognition failure that is not a hash failure. */
    private class ThrowingRecognition(private val failFor: Long) : BackupRecognitionRepository {
        override suspend fun recognize(media: LocalMedia): BackupRecognitionResult {
            if (media.id == failFor) throw IllegalStateException("recognition blew up")
            return BackupRecognitionResult.NeedsBackup("hash-${media.id}", media.sizeBytes)
        }
        override suspend fun markRecognized(media: LocalMedia, remote: RemoteMediaIdentity, hash: String, sizeBytes: Long) = Unit
    }

    private fun media(id: Long) = LocalMedia(
        id = id, contentUri = Uri.parse("content://m/$id"), type = MediaType.PHOTO, mimeType = "image/jpeg",
        displayName = "p$id.jpg", dateMillis = 1_000L, durationMillis = null, width = 1, height = 1, sizeBytes = 1L,
        bucketId = null, bucketName = null, relativePath = null,
    )

    private fun coordinator(
        repo: RecordingRepo,
        scheduler: FakeScheduler,
        preference: BackupPreference?,
        recognition: BackupRecognitionRepository = NeedsBackupRecognition(),
        pages: List<List<LocalMedia>> = listOf(listOf(media(1), media(2))),
        pageSize: Int = 100,
        buckets: Set<Long> = emptySet(),
        loaderFactory: ((Long?) -> com.telepic.data.media.MediaPageLoader)? = null,
    ) = DefaultBackupCoordinator(
        repository = repo,
        recognition = recognition,
        scheduler = scheduler,
        onboardingRepository = FakeOnboarding(preference, buckets),
        pageLoaderFactory = loaderFactory ?: { FakeLoader(pages, pageSize) },
        discoveryPageSize = pageSize,
    )

    @Test
    fun `BACKUP_ALL recognizes then enqueues eligible media and schedules a run`() = runBlocking {
        val repo = RecordingRepo()
        val scheduler = FakeScheduler()
        val recognition = NeedsBackupRecognition()
        coordinator(repo, scheduler, BackupPreference.BACKUP_ALL, recognition).syncFromPreference()
        assertEquals(2, repo.enqueuedWithHash.size)
        assertEquals(2, recognition.calls)
        assertEquals(1, scheduler.scheduled)
        // Automatic backup also installs the recurring 12h discovery cadence.
        assertEquals(1, scheduler.periodicScheduled)
    }

    @Test
    fun `NOT_NOW does not enqueue anything automatically`() = runBlocking {
        val repo = RecordingRepo()
        val scheduler = FakeScheduler()
        coordinator(repo, scheduler, BackupPreference.NOT_NOW).syncFromPreference()
        assertEquals(0, repo.enqueuedWithHash.size)
        assertEquals(0, scheduler.periodicScheduled)
    }

    @Test
    fun `SELECT_FOLDER with no folders picked backs up nothing (NOT_NOW equivalent)`() = runBlocking {
        val repo = RecordingRepo()
        val scheduler = FakeScheduler()
        coordinator(repo, scheduler, BackupPreference.SELECT_FOLDER).syncFromPreference()
        assertEquals(0, repo.enqueuedWithHash.size)
        assertEquals(0, scheduler.periodicScheduled)
    }

    @Test
    fun `SELECT_FOLDER scans exactly the chosen buckets and nothing else`() = runBlocking {
        val repo = RecordingRepo()
        val scheduler = FakeScheduler()
        val requested = mutableListOf<Long?>()
        val bucketPages = mapOf(
            11L to listOf(listOf(media(101))),
            22L to listOf(listOf(media(201), media(202))),
        )
        coordinator(
            repo, scheduler, BackupPreference.SELECT_FOLDER,
            buckets = setOf(11L, 22L),
            loaderFactory = { bucketId ->
                requested += bucketId
                val pages = if (bucketId == null) emptyList() else bucketPages[bucketId].orEmpty()
                FakeLoader(pages, pageSize = 100)
            },
        ).syncFromPreference()
        // Exactly one loader per chosen bucket — the whole library (null) is never read.
        assertEquals(listOf<Long?>(11L, 22L), requested)
        assertEquals(3, repo.enqueuedWithHash.size)
        assertEquals(1, scheduler.periodicScheduled)
        assertEquals(1, scheduler.scheduled)
    }

    @Test
    fun `manual backup enqueues with the recognized hash`() = runBlocking {
        val repo = RecordingRepo()
        val scheduler = FakeScheduler()
        coordinator(repo, scheduler, BackupPreference.NOT_NOW).backup(media(9))
        assertEquals(1, repo.enqueuedWithHash.size)
        assertEquals(1, scheduler.scheduled)
    }

    @Test
    fun `backupAll reports queued vs already-covered and only enqueues new items`() = runBlocking {
        val repo = RecordingRepo()
        val recognition = FixedRecognition { media ->
            if (media.id == 1L) {
                BackupRecognitionResult.AlreadyBackedUp("1", RemoteMediaIdentity(100L, 500L))
            } else {
                BackupRecognitionResult.NeedsBackup("hash-${media.id}", media.sizeBytes)
            }
        }
        val summary = coordinator(repo, FakeScheduler(), BackupPreference.NOT_NOW, recognition)
            .backupAll(listOf(media(1), media(2)))
        assertEquals(1, summary.queued)
        assertEquals(1, summary.alreadyCovered)
        // Only the genuinely-new item (id 2) reached the queue.
        assertEquals(1, repo.enqueuedWithHash.size)
        assertEquals(2L, repo.enqueuedWithHash.first().first.id)
    }

    @Test
    fun `already-backed-up and pending items are NOT enqueued again`() = runBlocking {
        val repo = RecordingRepo()
        val recognition = FixedRecognition { media ->
            if (media.id == 1L) {
                BackupRecognitionResult.AlreadyBackedUp("1", RemoteMediaIdentity(100L, 500L))
            } else {
                BackupRecognitionResult.Pending(7L)
            }
        }
        coordinator(repo, FakeScheduler(), BackupPreference.BACKUP_ALL, recognition, pages = listOf(listOf(media(1), media(2)))).syncFromPreference()
        assertEquals(0, repo.enqueuedWithHash.size)
        assertEquals(0, repo.enqueued.size)
    }

    @Test
    fun `incremental discovery pages until the library is exhausted`() = runBlocking {
        val repo = RecordingRepo()
        val pages = listOf(listOf(media(1), media(2)), listOf(media(3)))
        coordinator(repo, FakeScheduler(), BackupPreference.BACKUP_ALL, pages = pages, pageSize = 2).syncFromPreference()
        assertEquals(3, repo.enqueuedWithHash.size)
    }

    @Test
    fun `discovery keeps paging well past the old 2000-item head cap for large libraries`() = runBlocking {
        // 2,100 items in 21 full pages: the raised safety cap must not stop the scan at 2000.
        val pages = (1..21).map { p -> (1..100).map { media((p - 1) * 100L + it) } }
        val repo = RecordingRepo()
        coordinator(repo, FakeScheduler(), BackupPreference.BACKUP_ALL, pages = pages, pageSize = 100).syncFromPreference()
        assertEquals(2100, repo.enqueuedWithHash.size)
    }

    @Test
    fun `a page that fails mid-scan keeps what it already enqueued and does not throw`() = runBlocking {
        val repo = RecordingRepo()
        val scheduler = FakeScheduler()
        coordinator(
            repo, scheduler, BackupPreference.BACKUP_ALL,
            pageSize = 1,
            loaderFactory = { FailingLoader(listOf(listOf(media(1))), pageSize = 1) },
        ).syncFromPreference()
        // The first page's item is queued and scheduled; the failing second page ends the scan
        // rather than discarding that work or propagating a SecurityException into the caller.
        assertEquals(1, repo.enqueuedWithHash.size)
        assertEquals(1L, repo.enqueuedWithHash.first().first.id)
        assertEquals(1, scheduler.scheduled)
    }

    @Test
    fun `one failing bucket does not discard the buckets that already scanned`() = runBlocking {
        val repo = RecordingRepo()
        coordinator(
            repo, FakeScheduler(), BackupPreference.SELECT_FOLDER,
            buckets = setOf(11L, 22L),
            loaderFactory = { bucketId ->
                if (bucketId == 22L) {
                    FailingLoader(emptyList(), pageSize = 100)
                } else {
                    FakeLoader(listOf(listOf(media(101))), pageSize = 100)
                }
            },
        ).syncFromPreference()
        // Bucket 11's item survives bucket 22 failing outright.
        assertEquals(1, repo.enqueuedWithHash.size)
        assertEquals(101L, repo.enqueuedWithHash.first().first.id)
    }

    @Test
    fun `a recognition failure still queues the item rather than skipping it`() = runBlocking {
        val repo = RecordingRepo()
        coordinator(
            repo, FakeScheduler(), BackupPreference.BACKUP_ALL,
            recognition = ThrowingRecognition(failFor = 2L),
            pages = listOf(listOf(media(1), media(2), media(3))),
        ).syncFromPreference()
        // 1 and 3 recognized normally; 2 fell back to an unhashed enqueue — never silently skipped,
        // and never counted as already backed up.
        assertEquals(2, repo.enqueuedWithHash.size)
        assertEquals(1, repo.enqueued.size)
        assertEquals(2L, repo.enqueued.first().id)
    }

    @Test
    fun `a pass that cannot start cannot escape into its caller`() = runBlocking {
        // The reported failure: onboarding calls this straight from `viewModelScope`, so an
        // exception here became an uncaught main-thread exception and the app closed itself the
        // moment the user confirmed a folder. The pass now reports nothing and simply returns.
        val repo = RecordingRepo()
        coordinator(
            repo, FakeScheduler(), BackupPreference.BACKUP_ALL,
            loaderFactory = { _: Long? -> throw IllegalStateException("MediaStore unavailable") },
        ).syncFromPreference()
        assertEquals(0, repo.enqueuedWithHash.size)
        assertEquals(0, repo.enqueued.size)
    }

    @Test
    fun `a pass stopped mid-scan still hands its queued rows to the worker`() = runBlocking {
        // WorkManager cancels a long pass at its run limit. The rows enqueued before that stop used to
        // sit unattended until the next 12h tick — and the next tick does not schedule either, because
        // re-scanning an already-queued library resolves every item to Pending, not to new work. Only
        // opening the Backup Center and pressing Start could rescue them.
        val repo = RecordingRepo()
        val scheduler = FakeScheduler()
        val stoppingLoader = object : com.telepic.data.media.MediaPageLoader {
            private var calls = 0
            override suspend fun load(offset: Int, limit: Int): List<LocalMedia> {
                // A full first page (pageSize 2) keeps the scan going; the second is the stop.
                if (calls++ == 0) return listOf(media(1), media(2))
                throw CancellationException("work stopped")
            }
        }
        val coordinator = DefaultBackupCoordinator(
            repository = repo,
            recognition = NeedsBackupRecognition(),
            scheduler = scheduler,
            onboardingRepository = FakeOnboarding(BackupPreference.BACKUP_ALL),
            pageLoaderFactory = { stoppingLoader },
            discoveryPageSize = 2,
        )

        val thrown = runCatching { coordinator.syncFromPreference() }.exceptionOrNull()
        assertTrue("the stop must still propagate, got $thrown", thrown is CancellationException)
        assertEquals(2, repo.enqueuedWithHash.size)
        assertEquals("the pass must schedule the work it already enqueued", 1, scheduler.scheduled)
    }

    @Test
    fun `a completed pass schedules exactly one run`() = runBlocking {
        // The insurance above must not become a second worker run per tick on the normal path.
        val scheduler = FakeScheduler()
        coordinator(RecordingRepo(), scheduler, BackupPreference.BACKUP_ALL).syncFromPreference()
        assertEquals(1, scheduler.scheduled)
    }
}
