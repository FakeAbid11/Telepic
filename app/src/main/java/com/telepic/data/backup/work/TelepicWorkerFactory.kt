package com.telepic.data.backup.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.telepic.data.backup.BackupCoordinator
import com.telepic.data.backup.BackupRepository
import com.telepic.data.face.FaceIndexer
import com.telepic.data.face.FaceModelStore
import com.telepic.data.face.FaceRepository
import com.telepic.data.face.FaceSimilarityPreset
import com.telepic.data.face.work.FaceIndexWorker
import com.telepic.data.face.work.FaceWorkScheduler
import com.telepic.data.face.work.ModelDownloadWorker

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
    /**
     * The People tab's collaborators.
     *
     * All optional for the same reason [backupScheduler] is: a build or a test that does not wire the
     * People tab must still be able to construct this factory, and the workers then fail loudly if
     * WorkManager ever reaches them (which it cannot, because nothing schedules them).
     */
    private val faceModelStore: () -> FaceModelStore? = { null },
    private val faceIndexer: () -> FaceIndexer? = { null },
    private val faceRepository: () -> FaceRepository? = { null },
    private val faceWorkScheduler: () -> FaceWorkScheduler? = { null },
    /**
     * The similarity preset, read per run.
     *
     * Suspend because it reads a DataStore preference, and the worker calls it from its own coroutine.
     * Read per run rather than captured once, so a strictness change applies to the next scan instead
     * of the next app launch.
     */
    private val facePreset: suspend () -> FaceSimilarityPreset = { FaceSimilarityPreset.DEFAULT },
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
            ModelDownloadWorker::class.java.name ->
                ModelDownloadWorker(
                    appContext,
                    workerParameters,
                    faceModelStore() ?: error("the face model store is not wired"),
                )
            FaceIndexWorker::class.java.name ->
                FaceIndexWorker(
                    appContext,
                    workerParameters,
                    faceIndexer() ?: error("the face indexer is not wired"),
                    faceRepository() ?: error("the face repository is not wired"),
                    faceModelStore() ?: error("the face model store is not wired"),
                    faceWorkScheduler() ?: error("the face work scheduler is not wired"),
                    facePreset,
                )
            else -> null
        }
    }
}
