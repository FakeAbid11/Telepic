package com.telepic.data.backup

import com.telepic.data.backup.work.BackupWorkScheduler
import com.telepic.data.media.MediaPageLoader
import com.telepic.domain.backup.BackupRecognitionResult
import com.telepic.domain.media.LocalMedia
import com.telepic.onboarding.BackupPreference
import com.telepic.onboarding.OnboardingRepository
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

    fun observeQueue(): Flow<List<com.telepic.domain.backup.BackupItem>> = repository.observeQueue()
    fun observeStats(): Flow<com.telepic.domain.backup.BackupQueueStats> = repository.observeStats()

    /** Back up a single item explicitly (manual action). Recognition may turn this into a no-op. */
    suspend fun backup(media: LocalMedia): BulkBackupSummary

    /** Back up an explicit selection (manual multi-select action); reports queued vs already-covered. */
    suspend fun backupAll(media: List<LocalMedia>): BulkBackupSummary

    suspend fun retry(itemId: Long)
    suspend fun cancel(itemId: Long)

    /**
     * Apply the persisted preference: BACKUP_ALL scans the whole library, SELECT_FOLDER scans only
     * the chosen buckets (empty selection = nothing), NOT_NOW does nothing. Either automatic choice
     * installs the periodic discovery cadence, then incrementally discovers, recognizes and enqueues
     * only eligible *new* media and schedules a run.
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
 * SELECT_FOLDER scans exactly the buckets the user chose (per-bucket [MediaPageLoader]s); an empty
 * folder selection backs up nothing automatically — the honest equivalent of NOT_NOW, never a
 * silent fallback to the whole library.
 */
class DefaultBackupCoordinator(
    override val repository: BackupRepository,
    private val recognition: BackupRecognitionRepository,
    private val scheduler: BackupWorkScheduler,
    private val onboardingRepository: OnboardingRepository,
    private val pageLoaderFactory: (bucketId: Long?) -> MediaPageLoader,
    private val discoveryPageSize: Int = 100,
    private val discoveryMaxItems: Int = 25_000,
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
        when (onboardingRepository.backupPreference.first()) {
            BackupPreference.BACKUP_ALL -> {
                // Install the recurring 12h discovery cadence while automatic backup is on; the KEEP
                // policy makes this idempotent, and the worker re-checks the preference on every tick.
                scheduler.schedulePeriodic()
                if (discoverAndEnqueue(pageLoaderFactory(null))) scheduler.schedule()
            }
            BackupPreference.SELECT_FOLDER -> {
                val buckets = onboardingRepository.backupBucketIds.first()
                // Honest equivalence: "only these folders" with no folders picked backs up nothing.
                if (buckets.isEmpty()) return
                scheduler.schedulePeriodic()
                var enqueuedAny = false
                for (bucketId in buckets) {
                    if (discoverAndEnqueue(pageLoaderFactory(bucketId))) enqueuedAny = true
                }
                if (enqueuedAny) scheduler.schedule()
            }
            BackupPreference.NOT_NOW, null -> return
        }
    }

    override suspend fun startPendingBackup() {
        scheduler.schedule()
    }

    /**
     * Bounded, incremental scan of one loader (the whole library, or a single chosen bucket). The
     * cap is a safety ceiling for one pass, not the expected case: repeat scans are near-free because
     * every visited item ends with a queue row and recognition reuses the cached hash when
     * size+modified still match, so a pass interrupted by WorkManager's run window simply resumes
     * cheaply on the next tick (still from the newest head). Libraries larger than the cap converge
     * over consecutive periodic runs.
     */
    private suspend fun discoverAndEnqueue(pageLoader: MediaPageLoader): Boolean {
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
