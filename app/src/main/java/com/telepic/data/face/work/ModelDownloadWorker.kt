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
import com.telepic.data.face.FaceModelStore
import com.telepic.diagnostics.TelepicLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Downloads the face models, after the user has opted in.
 *
 * Runs in the foreground because it transfers a few megabytes and the user is watching a progress
 * indicator that must stay accurate — a plain background worker can be deferred by the system at
 * exactly the wrong moment, which looks like a hang.
 *
 * Failure policy: an [IOException] is transient (a flaky network, a captive portal) and is retried.
 * Anything else — a checksum mismatch, a missing asset — is not going to fix itself by trying again,
 * so it fails and the UI reports it, rather than retrying forever behind a progress bar that never
 * moves.
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
    private val store: FaceModelStore,
) : CoroutineWorker(appContext, params) {

    /**
     * Reflective constructor mirroring the backup workers' seam. WorkManager only reaches it when no
     * factory is configured, which cannot happen here; it fails loudly rather than reading a
     * process-global installed by the container.
     */
    constructor(appContext: Context, params: WorkerParameters) : this(
        appContext,
        params,
        error("ModelDownloadWorker must be created by TelepicWorkerFactory"),
    )

    override suspend fun doWork(): Result {
        if (store.isReady()) return Result.success()

        ensureChannel(applicationContext)
        setForegroundSafely(buildNotification(progress = null))

        // `setForeground` is suspend but `ensureDownloaded`'s progress callback is not, so updates are
        // pushed through a conflated channel and pumped by a sibling coroutine. Conflated on purpose:
        // only the newest progress value is ever worth showing, and a buffered channel would queue up
        // updates the user has already passed.
        val progress = Channel<Float?>(Channel.CONFLATED)

        return coroutineScope {
            val pump = launch {
                for (fraction in progress) setForegroundSafely(buildNotification(fraction))
            }

            try {
                store.ensureDownloaded { downloaded, total ->
                    progress.trySend(if (total > 0) downloaded.toFloat() / total else null)
                }
                Result.success()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (io: IOException) {
                TelepicLog.warn("ModelDownload", "model download failed; retrying", io)
                Result.retry()
            } catch (throwable: Throwable) {
                // A checksum mismatch lands here: retrying would download the same bytes and fail the
                // same way, so this is a permanent failure the UI must surface.
                TelepicLog.error("ModelDownload", "model download failed permanently", throwable)
                Result.failure()
            } finally {
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
            // A denied notification permission must not fail the download: the bytes still arrive and
            // the in-app progress bar still works.
            TelepicLog.warn("ModelDownload", "foreground notification unavailable; continuing", throwable)
        }
    }

    private fun buildNotification(progress: Float?): Notification {
        val text = if (progress == null) {
            applicationContext.getString(R.string.face_models_notification_starting)
        } else {
            applicationContext.getString(R.string.face_models_notification_progress, (progress * 100).toInt())
        }
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.face_models_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_people)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, progress?.let { (it * 100).toInt() } ?: 0, progress == null)
            .build()
    }

    /**
     * `dataSync`, matching the backup worker: since targetSdk 34 a foreground service started without
     * a declared type is rejected outright.
     */
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
                    context.getString(R.string.face_models_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 4202
        const val CHANNEL_ID = "telepic_face_models"
    }
}
