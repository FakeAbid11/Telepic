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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        override fun schedule() { scheduled++ }
        override fun cancel() = Unit
        override fun isScheduled() = false
    }

    private class FakeOnboarding(private val preference: BackupPreference?) : OnboardingRepository {
        override val isCompleted: Flow<Boolean> = flowOf(true)
        override val backupPreference: Flow<BackupPreference?> = flowOf(preference)
        override suspend fun setCompleted(completed: Boolean) = Unit
        override suspend fun setBackupPreference(preference: BackupPreference?) = Unit
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
    ) = DefaultBackupCoordinator(
        repository = repo,
        recognition = recognition,
        scheduler = scheduler,
        onboardingRepository = FakeOnboarding(preference),
        pageLoader = FakeLoader(pages, pageSize),
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
    }

    @Test
    fun `NOT_NOW does not enqueue anything automatically`() = runBlocking {
        val repo = RecordingRepo()
        coordinator(repo, FakeScheduler(), BackupPreference.NOT_NOW).syncFromPreference()
        assertEquals(0, repo.enqueuedWithHash.size)
    }

    @Test
    fun `SELECT_FOLDER is not faked as enforceable — no automatic enqueue`() = runBlocking {
        val repo = RecordingRepo()
        coordinator(repo, FakeScheduler(), BackupPreference.SELECT_FOLDER).syncFromPreference()
        assertEquals(0, repo.enqueuedWithHash.size)
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
}
