package com.telepix.data.backup

import com.telepix.data.backup.work.BackupWorkScheduler
import com.telepix.data.media.MediaPageLoader
import com.telepix.domain.backup.BackupRecognitionResult
import com.telepix.domain.media.LocalMedia
import com.telepix.onboarding.BackupPreference
import com.telepix.onboarding.OnboardingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Outcome of a manual backup request over one or more items, for honest UI feedback. [queued] counts
 * genuinely new work handed to the engine; [alreadyCovered] counts items recognized as already
 * backed up or with an in-flight/pending operation (so no duplicate upload was started). Failures at
 * this stage are none-by-design — a failed hash is still enqueued, never silently skipped.
 */
data class BulkBackupSummary(val queued: Int, val alreadyCovered: Int) {
    operator fun plus(other: BulkBackupSummary) =
        BulkBackupSummary(queued + other.queued, alreadyCovered + other.alreadyCovered)

    companion object {
        val Empty = BulkBackupSummary(0, 0)
    }
}

/**
 * The backup entry point the UI and startup use. It decides *what* becomes eligible (from the
 * Phase 2 backup preference and the Phase 3 MediaStore library), **recognizes it by content first**
 * (Phase 7) so already-backed-up or pending media is never re-queued, and hands new content to
 * [BackupRepository] + [BackupWorkScheduler]; it holds no upload/TDLib logic itself.
 */
interface BackupCoordinator {
    val repository: BackupRepository

    fun observeQueue(): Flow<List<com.telepix.domain.backup.BackupItem>> = repository.observeQueue()
    fun observeStats(): Flow<com.telepix.domain.backup.BackupQueueStats> = repository.observeStats()

    /** Back up a single item explicitly (manual action). Recognition may turn this into a no-op. */
    suspend fun backup(media: LocalMedia): BulkBackupSummary

    /** Back up an explicit selection (manual multi-select action); reports queued vs already-covered. */
    suspend fun backupAll(media: List<LocalMedia>): BulkBackupSummary

    suspend fun retry(itemId: Long)
    suspend fun cancel(itemId: Long)

    /**
     * Apply the persisted preference: when the user chose BACKUP_ALL, incrementally discover,
     * recognize and enqueue only eligible *new* media, then schedule a run. Honors NOT_NOW.
     */
    suspend fun syncFromPreference()

    /** Schedule a run for whatever is already queued (e.g. after the user enables backup later). */
    suspend fun startPendingBackup()
}

/**
 * Default [BackupCoordinator]. Automatic discovery is bounded and incremental (paged reads, never a
 * whole-library scan into memory). Recognition runs **before** enqueueing, so a repeated scan or a
 * reinstall (new MediaStore id, same bytes → same hash) recognizes existing content instead of
 * uploading a second copy.
 *
 * Honest limitation: SELECT_FOLDER is not enforceable yet — folder selection has no real
 * implementation, so the queue treats it as "back up nothing automatically" rather than pretending
 * to filter by folder. The pipeline is ready for a folder filter once selection exists.
 */
class DefaultBackupCoordinator(
    override val repository: BackupRepository,
    private val recognition: BackupRecognitionRepository,
    private val scheduler: BackupWorkScheduler,
    private val onboardingRepository: OnboardingRepository,
    private val pageLoader: MediaPageLoader,
    private val discoveryPageSize: Int = 100,
    private val discoveryMaxItems: Int = 2000,
) : BackupCoordinator {

    override suspend fun backup(media: LocalMedia): BulkBackupSummary {
        val result = recognizeAndMaybeEnqueue(media)
        if (result.queued > 0) scheduler.schedule()
        return result
    }

    override suspend fun backupAll(media: List<LocalMedia>): BulkBackupSummary {
        var total = BulkBackupSummary.Empty
        for (item in media) {
            total += recognizeAndMaybeEnqueue(item)
        }
        if (total.queued > 0) scheduler.schedule()
        return total
    }

    override suspend fun retry(itemId: Long) {
        repository.retry(itemId)
        scheduler.schedule()
    }

    override suspend fun cancel(itemId: Long) = repository.cancel(itemId)

    override suspend fun syncFromPreference() {
        val preference = onboardingRepository.backupPreference.first()
        if (preference != BackupPreference.BACKUP_ALL) return
        val scheduled = discoverAndEnqueue()
        if (scheduled) scheduler.schedule()
    }

    override suspend fun startPendingBackup() {
        scheduler.schedule()
    }

    private suspend fun discoverAndEnqueue(): Boolean {
        var offset = 0
        var seen = 0
        var enqueuedAny = false
        while (seen < discoveryMaxItems) {
            val page = pageLoader.load(offset, discoveryPageSize)
            if (page.isEmpty()) break
            for (item in page) {
                if (recognizeAndMaybeEnqueue(item).queued > 0) enqueuedAny = true
            }
            seen += page.size
            if (page.size < discoveryPageSize) break
            offset += page.size
        }
        return enqueuedAny
    }

    /**
     * Recognize one item and enqueue only when it genuinely needs backup. Returns a per-item summary
     * (1 queued, or 1 already-covered). A recognition failure still enqueues the item as queued — a
     * failed hash must never be mistaken for "already backed up", nor silently skipped.
     */
    private suspend fun recognizeAndMaybeEnqueue(media: LocalMedia): BulkBackupSummary =
        when (val result = recognition.recognize(media)) {
            is BackupRecognitionResult.AlreadyBackedUp -> BulkBackupSummary(0, 1) // associated; nothing to do
            is BackupRecognitionResult.Pending -> BulkBackupSummary(0, 1) // an active operation exists; no duplicate row
            is BackupRecognitionResult.NeedsBackup ->
                if (repository.enqueue(media, result.contentHash, result.contentSizeBytes)) BulkBackupSummary(1, 0)
                else BulkBackupSummary(0, 1) // already present in the queue
            is BackupRecognitionResult.Unavailable ->
                if (repository.enqueue(media)) BulkBackupSummary(1, 0) else BulkBackupSummary(0, 1)
        }
}
