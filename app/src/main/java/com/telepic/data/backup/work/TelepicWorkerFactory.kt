package com.telepic.data.backup.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.telepic.data.backup.BackupRepository

/**
 * Supplies [BackupWorker] its [BackupRepository] without a DI framework. WorkManager constructs
 * workers reflectively, so the container installs the repository here once and the factory hands it
 * to the worker. Unknown worker classes return null so WorkManager falls back to the default
 * reflectively-created workers (never a second Telegram client).
 */
class TelepicWorkerFactory(
    private val backupRepository: () -> BackupRepository,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        return if (workerClassName == BackupWorker::class.java.name) {
            BackupWorker(appContext, workerParameters, backupRepository())
        } else {
            null
        }
    }
}
