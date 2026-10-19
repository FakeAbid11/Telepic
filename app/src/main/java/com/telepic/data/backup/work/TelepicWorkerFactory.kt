package com.telepic.data.backup.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.telepic.data.backup.BackupCoordinator
import com.telepic.data.backup.BackupRepository

/**
 * Supplies [BackupWorker] its [BackupRepository] and [BackupDiscoveryWorker] its
 * [BackupCoordinator] without a DI framework. WorkManager constructs workers reflectively, so the
 * container installs these here once and the factory hands them to the workers. Unknown worker
 * classes return null so WorkManager falls back to the default reflectively-created workers (never
 * a second Telegram client).
 */
class TelepicWorkerFactory(
    private val backupRepository: () -> BackupRepository,
    private val backupCoordinator: () -> BackupCoordinator,
    /**
     * The scheduler the worker uses to hand the remainder of a long drain to a fresh run.
     *
     * Optional: a worker without one still works, it just falls back to `Result.retry()` — correct,
     * but it inherits the exponential backoff the continuation exists to avoid.
     */
    private val backupScheduler: () -> BackupWorkScheduler? = { null },
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        return when (workerClassName) {
            BackupWorker::class.java.name ->
                BackupWorker(appContext, workerParameters, backupRepository(), backupScheduler())
            BackupDiscoveryWorker::class.java.name ->
                BackupDiscoveryWorker(appContext, workerParameters, backupCoordinator())
            else -> null
        }
    }
}
