package com.telepix.data.backup

import android.net.Uri
import com.telepix.data.backup.work.BackupWorkScheduler
import com.telepix.domain.backup.BackupItem
import com.telepix.domain.backup.BackupQueueStats
import com.telepix.domain.media.LocalMedia
import com.telepix.domain.media.MediaType
import com.telepix.onboarding.BackupPreference
import com.telepix.onboarding.OnboardingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The coordinator honors the Phase 2 backup preference and only discovers incrementally. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupCoordinatorTest {

    private class RecordingRepo : BackupRepository {
        val enqueued = mutableListOf<LocalMedia>()
        var pendingStarted = false
        override fun observeQueue(): Flow<List<BackupItem>> = flowOf(emptyList())
        override fun observeStats(): Flow<BackupQueueStats> = flowOf(BackupQueueStats())
        override suspend fun enqueue(media: LocalMedia): Boolean {
            enqueued += media
            return true
        }

        override suspend fun enqueueAll(media: List<LocalMedia>): Int {
            enqueued += media
            return media.size
        }

        override suspend fun retry(itemId: Long) = Unit
        override suspend fun cancel(itemId: Long) = Unit
        override suspend fun recoverInterruptedWork() = Unit
        override suspend fun processPendingWork(maxItems: Int) = BackupProcessSummary()
    }

    private class FakeScheduler : BackupWorkScheduler {
        var scheduled = 0
        var cancelled = 0
        override fun schedule() { scheduled++ }
        override fun cancel() { cancelled++ }
        override fun isScheduled() = false
    }

    private class FakeOnboarding(private val preference: BackupPreference?) : OnboardingRepository {
        override val isCompleted: Flow<Boolean> = flowOf(true)
        override val backupPreference: Flow<BackupPreference?> = flowOf(preference)
        override suspend fun setCompleted(completed: Boolean) = Unit
        override suspend fun setBackupPreference(preference: BackupPreference?) = Unit
    }

    private class FakeLoader(private val pages: List<List<LocalMedia>>, private val pageSize: Int) :
        com.telepix.data.media.MediaPageLoader {
        override suspend fun load(offset: Int, limit: Int): List<LocalMedia> =
            pages.getOrElse(offset / pageSize) { emptyList() }
    }

    private fun media(id: Long) = LocalMedia(
        id = id, contentUri = Uri.parse("content://m/$id"), type = MediaType.PHOTO, mimeType = "image/jpeg",
        displayName = "p$id.jpg", dateMillis = 1L, durationMillis = null, width = 1, height = 1, sizeBytes = 1L,
        bucketId = null, bucketName = null, relativePath = null,
    )

    private fun coordinator(
        repo: RecordingRepo,
        scheduler: FakeScheduler,
        preference: BackupPreference?,
        pages: List<List<LocalMedia>> = listOf(listOf(media(1), media(2))),
        pageSize: Int = 100,
    ) = DefaultBackupCoordinator(
        repository = repo,
        scheduler = scheduler,
        onboardingRepository = FakeOnboarding(preference),
        pageLoader = FakeLoader(pages, pageSize),
        discoveryPageSize = pageSize,
    )

    @Test
    fun `BACKUP_ALL discovers and enqueues eligible media then schedules a run`() = runBlocking {
        val repo = RecordingRepo()
        val scheduler = FakeScheduler()
        coordinator(repo, scheduler, BackupPreference.BACKUP_ALL).syncFromPreference()
        assertEquals(2, repo.enqueued.size)
        assertEquals(1, scheduler.scheduled)
    }

    @Test
    fun `NOT_NOW does not enqueue anything automatically`() = runBlocking {
        val repo = RecordingRepo()
        val scheduler = FakeScheduler()
        coordinator(repo, scheduler, BackupPreference.NOT_NOW).syncFromPreference()
        assertEquals(0, repo.enqueued.size)
        assertEquals(0, scheduler.scheduled)
    }

    @Test
    fun `SELECT_FOLDER is not faked as enforceable — no automatic enqueue`() = runBlocking {
        val repo = RecordingRepo()
        coordinator(repo, FakeScheduler(), BackupPreference.SELECT_FOLDER).syncFromPreference()
        assertEquals(0, repo.enqueued.size)
    }

    @Test
    fun `manual backup enqueues one item and schedules`() = runBlocking {
        val repo = RecordingRepo()
        val scheduler = FakeScheduler()
        coordinator(repo, scheduler, BackupPreference.NOT_NOW).backup(media(9))
        assertEquals(1, repo.enqueued.size)
        assertEquals(1, scheduler.scheduled)
    }

    @Test
    fun `incremental discovery pages until the library is exhausted`() = runBlocking {
        val repo = RecordingRepo()
        val pages = listOf(listOf(media(1), media(2)), listOf(media(3)))
        val c = coordinator(repo, FakeScheduler(), BackupPreference.BACKUP_ALL, pages, pageSize = 2)
        c.syncFromPreference()
        // First page is full (2), so discovery continues; the short second page stops it.
        assertEquals(3, repo.enqueued.size)
    }
}
