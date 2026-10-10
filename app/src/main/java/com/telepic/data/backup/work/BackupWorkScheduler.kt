package com.telepic.data.backup.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * The WorkManager orchestration seam. Keeping it behind an interface lets the coordinator be tested
 * with a fake scheduler, while the real one drives a network-constrained, resumable [BackupWorker].
 */
interface BackupWorkScheduler {
    /** Ensure a run is scheduled (unique; won't stack duplicate workers). */
    fun schedule()

    /** Stop active work cooperatively; queued rows stay QUEUED. */
    fun cancel()

    fun isScheduled(): Boolean
}

const val BACKUP_WORK_NAME = "telepic_backup"

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

    override fun cancel() {
        workManager.cancelUniqueWork(BACKUP_WORK_NAME)
    }

    override fun isScheduled(): Boolean =
        workManager.getWorkInfosForUniqueWork(BACKUP_WORK_NAME).get()
            .orEmpty().any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

    private companion object {
        const val BACKOFF_MILLIS = 10_000L
    }
}
