package com.telepix.data.backup

import com.telepix.data.media.MediaPageLoader
import com.telepix.domain.media.LocalMedia
import com.telepix.onboarding.BackupPreference
import com.telepix.onboarding.OnboardingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * The backup entry point the UI and startup use. It decides *what* becomes eligible (from the
 * Phase 2 backup preference and the Phase 3 MediaStore library) and hands the work to
 * [BackupRepository] + [BackupWorkScheduler]; it holds no upload/TDLib logic itself.
 */
interface BackupCoordinator {
    val repository: BackupRepository

    fun observeQueue() = repository.observeQueue()
    fun observeStats() = repository.observeStats()

    /** Back up a single item explicitly (manual action). */
    suspend fun backup(media: LocalMedia)

    /** Back up an explicit selection (manual multi-select action). */
    suspend fun backupAll(media: List<LocalMedia>)

    suspend fun retry(itemId: Long)
    suspend fun cancel(itemId: Long)

    /**
     * Apply the persisted preference: when the user chose BACKUP_ALL, incrementally discover and
     * enqueue eligible local media, then schedule a run. Honors NOT_NOW (no automatic enqueue).
     */
    suspend fun syncFromPreference()

    /** Schedule a run for whatever is already queued (e.g. after the user enables backup later). */
    suspend fun startPendingBackup()
}

/**
 * Default [BackupCoordinator]. Automatic discovery is bounded and incremental (paged reads, never a
 * whole-library scan into memory) and respects the preference.
 *
 * Honest limitation: SELECT_FOLDER is not enforceable yet — folder selection has no real
 * implementation, so the queue treats it as "back up nothing automatically" rather than pretending
 * to filter by folder. The pipeline is ready for a folder filter once selection exists.
 */
class DefaultBackupCoordinator(
    override val repository: BackupRepository,
    private val scheduler: BackupWorkScheduler,
    private val onboardingRepository: OnboardingRepository,
    private val pageLoader: MediaPageLoader,
    private val discoveryPageSize: Int = 100,
    private val discoveryMaxItems: Int = 2000,
) : BackupCoordinator {

    override suspend fun backup(media: LocalMedia) {
        repository.enqueue(media)
        scheduler.schedule()
    }

    override suspend fun backupAll(media: List<LocalMedia>) {
        if (media.isNotEmpty()) {
            repository.enqueueAll(media)
            scheduler.schedule()
        }
    }

    override suspend fun retry(itemId: Long) {
        repository.retry(itemId)
        scheduler.schedule()
    }

    override suspend fun cancel(itemId: Long) = repository.cancel(itemId)

    override suspend fun syncFromPreference() {
        val preference = onboardingRepository.backupPreference.first()
        if (preference != BackupPreference.BACKUP_ALL) return
        discoverAndEnqueue()
        scheduler.schedule()
    }

    override suspend fun startPendingBackup() {
        scheduler.schedule()
    }

    private suspend fun discoverAndEnqueue() {
        var offset = 0
        var enqueued = 0
        while (enqueued < discoveryMaxItems) {
            val page = pageLoader.load(offset, discoveryPageSize)
            if (page.isEmpty()) break
            enqueued += repository.enqueueAll(page)
            if (page.size < discoveryPageSize) break
            offset += page.size
        }
    }
}
