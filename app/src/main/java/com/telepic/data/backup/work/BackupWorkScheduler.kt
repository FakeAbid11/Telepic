package com.telepic.data.backup.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * The WorkManager orchestration seam. Keeping it behind an interface lets the coordinator be tested
 * with a fake scheduler, while the real one drives a network-constrained, resumable [BackupWorker].
 *
 * There is deliberately no cancel/isScheduled pair: opting out of backup is enforced by the
 * preference gate inside [BackupDiscoveryWorker] on every tick, and killing the unique one-time work
 * from here would cancel an in-flight upload mid-TDLib-send and strand rows in UPLOADING. Do not
 * reintroduce a cancel path without handling that.
 */
interface BackupWorkScheduler {
    /** Ensure a run is scheduled (unique; won't stack duplicate workers). */
    fun schedule()

    /** Ensure the recurring discovery+processing cadence is installed (unique; idempotent). */
    fun schedulePeriodic()
}

const val BACKUP_WORK_NAME = "telepic_backup"
const val BACKUP_PERIODIC_WORK_NAME = "telepic_backup_periodic"

class WorkManagerBackupScheduler(private val context: Context) : BackupWorkScheduler {

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        // KEEP: if a run is already pending/running, don't restart it — new enqueues will be picked
        // up by the running worker's loop. REPLACE would cancel an in-flight upload for no reason.
        workManager.enqueueUniqueWork(BACKUP_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<BackupDiscoveryWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        // KEEP, same rationale as the one-time path: the periodic spec is static, so UPDATE would
        // only risk interrupting an in-flight discovery run for zero benefit.
        workManager.enqueueUniquePeriodicWork(BACKUP_PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val BACKOFF_MILLIS = 10_000L

        /** Background discovery cadence: new media left on the device is picked up within 12h. */
        const val PERIOD_HOURS = 12L
    }
}
