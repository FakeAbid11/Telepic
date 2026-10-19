package com.telepic.data.backup.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.telepic.diagnostics.TelepicLog
import com.telepic.settings.BackupNetworkPreference
import com.telepic.settings.SettingsRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * The WorkManager orchestration seam. Keeping it behind an interface lets the coordinator be tested
 * with a fake scheduler, while the real one drives a constrained, resumable [BackupWorker].
 *
 * Both methods are `suspend` because the constraints they build come from the user's persisted
 * transport preferences, which have to be read first.
 *
 * There is deliberately no cancel/isScheduled pair: opting out of backup is enforced by the
 * preference gate inside [BackupDiscoveryWorker] on every tick, and killing the unique one-time work
 * from here would cancel an in-flight upload mid-TDLib-send and strand rows in UPLOADING. Do not
 * reintroduce a cancel path without handling that.
 */
interface BackupWorkScheduler {
    /** Ensure a run is scheduled (unique; won't stack duplicate workers). */
    suspend fun schedule()

    /** Ensure the recurring discovery+processing cadence is installed (unique; idempotent). */
    suspend fun schedulePeriodic()

    /**
     * Hand the rest of a drain to a **fresh** run, without inheriting this run's backoff.
     *
     * `Result.retry()` re-runs the *same* WorkSpec, so WorkManager's exponential backoff grows with
     * every attempt and pins at its five-hour cap. That is the right behaviour for an item that
     * keeps failing, and the wrong behaviour for a queue that simply has more items than one batch
     * holds: a ten-thousand-item library needs a hundred batches, and the drain took about twenty
     * days. A continuation is a new spec, so it starts with a fresh backoff.
     *
     * The default implementation delegates to [schedule], which is the right thing for a scheduler
     * that does not distinguish the two.
     */
    suspend fun scheduleContinuation() = schedule()
}

const val BACKUP_WORK_NAME = "telepic_backup"
const val BACKUP_PERIODIC_WORK_NAME = "telepic_backup_periodic"

/**
 * The real scheduler. Two things make the user's transport preferences actually mean something:
 *
 * 1. The constraints are rebuilt from [SettingsRepository] on every call, so "Wi-Fi only" becomes
 *    `NetworkType.UNMETERED` and WorkManager simply never starts the run on mobile data — the queue
 *    stays `QUEUED` and nothing is failed for a condition the user chose.
 * 2. A changed preference has to reach work that is already enqueued. `KEEP` would ignore the new
 *    request entirely, so the old constraints would keep applying until the current run drained —
 *    which, because the worker returns `Result.retry()` while rows remain, can be a very long time.
 *    The one-time path therefore uses `REPLACE`, but only when nothing is currently *running*:
 *    replacing a running upload would cancel a TDLib send mid-flight and strand its row in UPLOADING,
 *    which is the exact failure the old `KEEP` existed to avoid. The periodic path can use `UPDATE`,
 *    which applies the new spec without cancelling a running instance at all.
 */
class WorkManagerBackupScheduler(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) : BackupWorkScheduler {

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    override suspend fun schedule() {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(transportConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        // KEEP while a run is in flight — new enqueues are picked up by the running worker's own
        // loop, and REPLACE would cancel an in-flight upload for no reason. Otherwise REPLACE, so a
        // just-changed constraint takes effect now instead of after the current work drains.
        val policy = if (isRunning(BACKUP_WORK_NAME)) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        workManager.enqueueUniqueWork(BACKUP_WORK_NAME, policy, request)
    }

    override suspend fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<BackupDiscoveryWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(transportConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        // UPDATE rewrites the existing spec in place without cancelling a running discovery pass, so
        // a changed constraint applies from the next period on and an in-flight pass is never cut off.
        workManager.enqueueUniquePeriodicWork(BACKUP_PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Continue a drain with a fresh run.
     *
     * `REPLACE`, not `KEEP`: the caller is the run that is finishing *right now*, and `KEEP` drops
     * the continuation because that run is still marked RUNNING. `REPLACE` swaps the finished run
     * for the new spec, which also resets the exponential backoff — the whole point of a
     * continuation.
     */
    override suspend fun scheduleContinuation() {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(transportConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(BACKUP_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * The user's transport preferences as WorkManager constraints.
     *
     * An unreadable preference falls back to the same defaults the repository declares (any network,
     * no charging/battery requirement), so a DataStore failure relaxes the run rather than silently
     * wedging backup forever behind a constraint nobody chose.
     */
    private suspend fun transportConstraints(): Constraints {
        val network = readPreference(BackupNetworkPreference.Default) {
            settingsRepository.backupNetworkPreference.first()
        }
        val requiresCharging = readPreference(false) { settingsRepository.backupRequiresCharging.first() }
        val requiresBatteryNotLow = readPreference(false) {
            settingsRepository.backupRequiresBatteryNotLow.first()
        }
        return Constraints.Builder()
            .setRequiredNetworkType(
                if (network == BackupNetworkPreference.WIFI_ONLY) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .setRequiresCharging(requiresCharging)
            .setRequiresBatteryNotLow(requiresBatteryNotLow)
            .build()
    }

    /**
     * Whether work with [name] is currently executing.
     *
     * Fails closed (reports "running") so an unreadable WorkManager state makes the caller keep the
     * existing work rather than replace it — the safe direction is always "do not cancel an upload".
     */
    private suspend fun isRunning(name: String): Boolean = try {
        workManager.getWorkInfosForUniqueWorkFlow(name).first()
            .any { it.state == WorkInfo.State.RUNNING }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        TelepicLog.warn("BackupScheduler", "WorkManager state unreadable; keeping the existing run", throwable)
        true
    }

    /**
     * Reads one preference, falling back to [fallback] when it cannot be read.
     *
     * A cancelled read is rethrown rather than treated as a fallback: the caller's scope is going away,
     * and substituting a default would let a cancelled pass still enqueue work under constraints the
     * user did not choose. (The repository's own flows already degrade a read error to their default,
     * so this is the second line of defence.)
     */
    private suspend fun <T> readPreference(fallback: T, read: suspend () -> T): T = try {
        read()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        // Worth a line: the run then happens under constraints the user never chose (e.g. mobile data
        // instead of Wi-Fi only), which is otherwise indistinguishable from the preference being ignored.
        TelepicLog.warn("BackupScheduler", "transport preference unreadable; using the default", throwable)
        fallback
    }

    private companion object {
        const val BACKOFF_MILLIS = 10_000L

        /** Background discovery cadence: new media left on the device is picked up within 12h. */
        const val PERIOD_HOURS = 12L
    }
}
