package com.telepic.data.backup

import com.telepic.data.ScanCursorStore
import com.telepic.data.backup.work.BackupWorkScheduler
import com.telepic.data.media.MediaPageLoader
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.backup.BackupRecognitionResult
import com.telepic.domain.media.LocalMedia
import com.telepic.onboarding.BackupPreference
import com.telepic.onboarding.OnboardingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

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
 * whole-library scan into memory). Recognition runs **before** enqueueing, so a repeated scan
 * recognizes existing content instead of uploading a second copy.
 *
 * What that dedup can and cannot do: the manifest's `contentHash` is written by the upload path
 * (`TelegramCloudRepository`) and, for a manifest rebuilt from the channel's history, recovered from
 * the message caption (`CloudBackupMarker`) — so after a reinstall recognition still matches, because
 * `refreshLocked` walks the whole history rather than only its newest page. What it cannot do is
 * recognise media backed up by a build that predates the caption marker: those rows carry no hash, and
 * `findHashlessByNameAndSize` only recovers the kinds Telegram gives a filename to (videos, GIFs and
 * documents — never a photo). Within one install, dedup is by content hash, and identical bytes
 * reached through a different local id are caught by `BackupQueueDao.findActiveByContentHash`.
 *
 * Discovery excludes **trashed** media only, never archived: see `AppContainer.backupDiscoveryLoader`.
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
    private val cursorStore: ScanCursorStore,
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
        try {
            applyPreference()
        } catch (cancellation: CancellationException) {
            throw cancellation // a cancelled pass is not a failure to report
        } catch (throwable: Throwable) {
            // An automatic pass is a background concern and must never be able to kill its caller.
            // This used to be unguarded, and the onboarding backup step calls it straight from
            // `viewModelScope` — so any failure in the scan (a revoked or limited media grant, a
            // provider error, an unreadable file, a WorkManager init failure) became an uncaught
            // exception on the main thread and the process died the instant the user confirmed a
            // folder. By that point the choice is already persisted and the periodic cadence is
            // already installed, so the right outcome is to carry on and let the next tick retry —
            // never to take the app down.
            //
            // Nothing is claimed as backed up here: an interrupted pass leaves rows unqueued, and
            // the queue is the only thing that ever reports success.
            TelepicLog.warn("BackupCoordinator", "automatic backup pass aborted, retrying next tick", throwable)
        }
    }

    private suspend fun applyPreference() {
        when (onboardingRepository.backupPreference.first()) {
            BackupPreference.BACKUP_ALL -> {
                // Install the recurring 12h discovery cadence while automatic backup is on; the KEEP
                // policy makes this idempotent, and the worker re-checks the preference on every tick.
                scheduler.schedulePeriodic()
                val enqueued = try {
                    discoverAndEnqueue(pageLoaderFactory(null), cursorKey(null))
                } catch (cancellation: CancellationException) {
                    scheduleInterruptedPass()
                    throw cancellation
                }
                if (enqueued) scheduler.schedule()
            }
            BackupPreference.SELECT_FOLDER -> {
                val buckets = onboardingRepository.backupBucketIds.first()
                // Honest equivalence: "only these folders" with no folders picked backs up nothing.
                if (buckets.isEmpty()) return
                scheduler.schedulePeriodic()
                val enqueuedAny = try {
                    var any = false
                    for (bucketId in buckets) {
                        if (discoverAndEnqueue(pageLoaderFactory(bucketId), cursorKey(bucketId))) any = true
                    }
                    any
                } catch (cancellation: CancellationException) {
                    scheduleInterruptedPass()
                    throw cancellation
                }
                if (enqueuedAny) scheduler.schedule()
            }
            BackupPreference.NOT_NOW, null -> return
        }
    }

    /**
     * Hand the queue to the worker after a scan that was **cut short**, which the normal
     * `if (enqueued) schedule()` never does because the exception skips past it.
     *
     * WorkManager stops a long-running worker on its run limit; rows enqueued before that stop then sat
     * unattended until the next 12h tick — and the next tick does not schedule either, since
     * re-scanning an already-queued library resolves every item to `Pending`, not to new work
     * ([discoverAndEnqueue] returns false). Only a manual start from the Backup Center could rescue it.
     *
     * Runs under [NonCancellable] on purpose: the caller's job is mid-cancellation, and
     * [BackupWorkScheduler.schedule] suspends to read the transport preferences and WorkManager state
     * before it reaches `enqueueUniqueWork` — those reads would otherwise throw before any work was
     * scheduled. A completed pass is unaffected, so the one-schedule-per-pass contract holds.
     */
    private suspend fun scheduleInterruptedPass() {
        try {
            withContext(NonCancellable) { scheduler.schedule() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            TelepicLog.warn("BackupCoordinator", "could not schedule work after an interrupted pass", throwable)
        }
    }

    override suspend fun startPendingBackup() {
        scheduler.schedule()
    }

    /**
     * Bounded, incremental scan of one loader (the whole library, or a single chosen bucket).
     *
     * The cap is a ceiling on **one** pass, measured from wherever [cursorKey]'s cursor points. That
     * cursor is persisted, so a library larger than [discoveryMaxItems] converges over successive
     * passes instead of re-examining the same newest slice forever. A pass that walks the loader to
     * its end rewinds the cursor to 0 — that is what keeps newly added media discoverable — while a
     * capped or interrupted one leaves it at the next unexamined offset.
     *
     * What a repeat pass costs little is re-visiting rows it already queued: recognition reuses the
     * cached hash while size + modified still match, and a stop mid-way still hands the enqueued rows
     * to the worker — see [scheduleInterruptedPass].
     */
    private suspend fun discoverAndEnqueue(pageLoader: MediaPageLoader, cursorKey: String): Boolean {
        var offset = cursorStore.offset(cursorKey).coerceAtLeast(0)
        var seen = 0
        var enqueuedAny = false
        var reachedEnd = false
        while (seen < discoveryMaxItems) {
            val page = try {
                pageLoader.load(offset, discoveryPageSize)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // One unreadable page ends *this* loader, not the pass. A MediaStore query can fail on
                // its own — a revoked or limited media grant, a provider error — and that must not
                // discard the buckets already scanned, nor propagate into whatever is awaiting the
                // pass (the periodic worker, the app's startup scope, an onboarding ViewModel). The
                // cursor stays where it is, so the next pass resumes at the page that failed rather
                // than skipping past it.
                return enqueuedAny
            }
            if (page.isEmpty()) {
                reachedEnd = true
                break
            }
            for (item in page) {
                if (recognizeAndMaybeEnqueue(item).queued > 0) enqueuedAny = true
            }
            seen += page.size
            offset += page.size
            if (page.size < discoveryPageSize) {
                reachedEnd = true
                break
            }
        }
        persistCursor(cursorKey, if (reachedEnd) 0 else offset)
        return enqueuedAny
    }

    /** The key a loader's progress is stored under: one for the whole library, one per bucket. */
    private fun cursorKey(bucketId: Long?): String =
        if (bucketId == null) CURSOR_KEY_ALL else "bucket_$bucketId"

    /**
     * Record discovery progress. Best-effort on purpose: the cursor is an optimisation, so a store that
     * cannot be written must not turn a scan that already enqueued work into a failure.
     */
    private suspend fun persistCursor(key: String, offset: Int) {
        try {
            cursorStore.save(key, offset)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            TelepicLog.warn("BackupCoordinator", "could not persist the discovery cursor for '$key'", throwable)
        }
    }

    /**
     * Recognize one item and enqueue only when it genuinely needs backup. Returns a per-item summary
     * (1 queued, or 1 already-covered). A recognition failure still enqueues the item as queued — a
     * failed hash must never be mistaken for "already backed up", nor silently skipped.
     *
     * Never throws for a per-item reason: one bad file cannot be allowed to abort a whole library
     * scan. If even the fallback enqueue fails, the summary is neither queued nor covered — an
     * honest "nothing happened", never a fabricated "already backed up".
     */
    private suspend fun recognizeAndMaybeEnqueue(media: LocalMedia): BulkBackupSummary =
        try {
            when (val result = recognition.recognize(media)) {
                is BackupRecognitionResult.AlreadyBackedUp -> BulkBackupSummary(0, 1) // associated; nothing to do
                is BackupRecognitionResult.Pending -> BulkBackupSummary(0, 1) // an active operation exists; no duplicate row
                is BackupRecognitionResult.NeedsBackup ->
                    if (repository.enqueue(media, result.contentHash, result.contentSizeBytes)) BulkBackupSummary(1, 0)
                    else BulkBackupSummary(0, 1) // already present in the queue
                is BackupRecognitionResult.Unavailable ->
                    if (repository.enqueue(media)) BulkBackupSummary(1, 0) else BulkBackupSummary(0, 1)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Recognition failed outside its own boundaries (a database error, an unexpected
            // provider throwable). Keep the documented rule — an item is never silently skipped —
            // by queueing it unhashed and letting the worker decide.
            enqueueUnhashed(media)
        }

    private suspend fun enqueueUnhashed(media: LocalMedia): BulkBackupSummary = try {
        if (repository.enqueue(media)) BulkBackupSummary(1, 0) else BulkBackupSummary(0, 1)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        BulkBackupSummary.Empty // neither queued nor covered: nothing is claimed about this item
    }

    private companion object {
        /** Cursor key for the whole-library loader; per-bucket loaders use `bucket_<id>`. */
        const val CURSOR_KEY_ALL = "all"
    }
}
