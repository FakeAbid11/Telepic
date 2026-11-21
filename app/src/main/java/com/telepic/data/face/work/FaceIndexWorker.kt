package com.telepic.data.face.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.telepic.R
import com.telepic.data.face.ChunkResult
import com.telepic.data.face.FaceIndexer
import com.telepic.data.face.FaceModelStore
import com.telepic.data.face.FaceRepository
import com.telepic.data.face.FaceSimilarityPreset
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.face.ScanProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Builds the face index, one bounded run at a time.
 *
 * **Why one run is bounded.** A scan of a 10,000-photo library is several minutes of continuous
 * decoding and inference. Holding a foreground service open for that long is exactly what Android
 * discourages, and a single unbounded worker would also lose all progress on process death. Instead
 * each run examines at most [FaceIndexer.DEFAULT_MAX_PHOTOS], commits its work, and — if there is more
 * library left — schedules a continuation with a **fresh** spec rather than returning `Result.retry()`,
 * which would inherit WorkManager's growing exponential backoff and stretch the scan over days.
 *
 * **Why clustering happens at the end of every run, not per chunk.** The indexer's groups are
 * provisional while a scan is in flight: a face that arrives early can only link to what has already
 * been seen. Rebuilding after each run means the user can look at the People tab at any point and see
 * a coherent grouping of everything scanned so far, rather than a snapshot frozen at the start.
 */
class FaceIndexWorker(
    appContext: Context,
    params: WorkerParameters,
    private val indexer: FaceIndexer,
    private val repository: FaceRepository,
    private val store: FaceModelStore,
    private val scheduler: FaceWorkScheduler,
    /** Read per run, so a strictness change applies to the next scan rather than the next launch. */
    private val preset: suspend () -> FaceSimilarityPreset,
) : CoroutineWorker(appContext, params) {

    /** Reflective seam; see [ModelDownloadWorker]'s equivalent constructor. */
    constructor(appContext: Context, params: WorkerParameters) : this(
        appContext,
        params,
        error("FaceIndexWorker must be created by TelepicWorkerFactory"),
        error("FaceIndexWorker must be created by TelepicWorkerFactory"),
        error("FaceIndexWorker must be created by TelepicWorkerFactory"),
        error("FaceIndexWorker must be created by TelepicWorkerFactory"),
        error("FaceIndexWorker must be created by TelepicWorkerFactory"),
    )

    override suspend fun doWork(): Result {
        // The models can vanish between being scheduled and running (the user removed face data, or
        // the download failed). Scanning without them would crash inside the interpreter.
        if (!store.isReady()) {
            TelepicLog.warn("FaceIndex", "models are not present; nothing to index")
            return Result.success()
        }

        ensureChannel(applicationContext)
        setForegroundSafely(buildNotification(photos = 0, faces = 0))

        // The indexer's chunk callback is not suspend, so progress is pushed through a conflated
        // channel and pumped by a sibling coroutine — the same shape as `ModelDownloadWorker`.
        val progress = Channel<ChunkResult>(Channel.CONFLATED)

        return coroutineScope {
            val pump = launch {
                for (chunk in progress) {
                    setForegroundSafely(buildNotification(chunk.runningPhotoTotal, chunk.runningFaceTotal))
                }
            }

            try {
                val result = indexer.index { chunk ->
                    // Published for the screen: a scan that has examined 800 photos and found nothing
                    // looks identical to one that has not started unless this is reported.
                    repository.reportScanProgress(ScanProgress(chunk.runningPhotoTotal, chunk.runningFaceTotal))
                    progress.trySend(chunk)
                }

                // Rebuild after the run, not before: a rebuild with no new faces is wasted work, and
                // the user-visible grouping only has to be consistent when the run has finished
                // committing.
                if (result.facesFound > 0 || result.photosExamined > 0) {
                    repository.rebuildClusters(preset())
                }

                if (result.reachedEnd) {
                    Result.success()
                } else {
                    // More library remains. A fresh spec starts with a fresh backoff, which is what
                    // stops a large library's scan from stretching out to days.
                    scheduler.scheduleIndexing()
                    Result.success()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                TelepicLog.error("FaceIndex", "indexing run failed", throwable)
                // Not `retry()`: a failure here is most likely a corrupt model file or an
                // out-of-memory condition on a huge photo, and neither improves by trying again
                // immediately. The continuation path above handles "there is simply more to do".
                Result.failure()
            } finally {
                // Cleared on every exit path, so a stopped or failed run cannot leave the screen
                // showing a progress line that will never advance again.
                repository.reportScanProgress(null)
                progress.close()
                pump.join()
            }
        }
    }

    private suspend fun setForegroundSafely(notification: Notification) {
        try {
            setForeground(foregroundInfo(notification))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            TelepicLog.warn("FaceIndex", "foreground notification unavailable; scanning continues", throwable)
        }
    }

    private fun buildNotification(photos: Int, faces: Int): Notification {
        val text = if (photos > 0) {
            applicationContext.getString(R.string.face_index_notification_progress, photos, faces)
        } else {
            applicationContext.getString(R.string.face_index_notification_starting)
        }
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.face_index_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_people)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()
    }

    private fun foregroundInfo(notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.face_index_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 4203
        const val CHANNEL_ID = "telepic_face_index"
    }
}
