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
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        return when (workerClassName) {
            BackupWorker::class.java.name -> BackupWorker(appContext, workerParameters, backupRepository())
            BackupDiscoveryWorker::class.java.name ->
                BackupDiscoveryWorker(appContext, workerParameters, backupCoordinator())
            else -> null
        }
    }
}
