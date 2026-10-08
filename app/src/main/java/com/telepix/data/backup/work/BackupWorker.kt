package com.telepix.data.backup.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.telepix.R
import com.telepix.data.backup.BackupRepository

/**
 * Process-wide handle the container installs so the WorkManager-created worker can reach the
 * [BackupRepository] without a DI framework. Set once in [com.telepix.di.AppContainer].
 */
object BackupWorkerDependencies {
    @Volatile
    var repository: BackupRepository? = null
}

/**
 * The single backup [CoroutineWorker]. It never depends on an Activity, Composable, or screen being
 * alive. It runs the resumable queue loop off the main thread, reports an honest foreground
 * notification ("Uploading n of m"), and cooperatively stops on cancel.
 *
 * WorkManager only starts this when a network connection is available (the scheduler's constraint),
 * so an offline device simply leaves rows QUEUED until connectivity returns — nothing is failed for
 * being offline, and nothing is ever marked BACKED_UP without Telegram's confirmation.
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
    private val repository: BackupRepository,
) : CoroutineWorker(appContext, params) {

    /** Reflective constructor WorkManager uses when no factory provides the repository. */
    constructor(appContext: Context, params: WorkerParameters) : this(
        appContext,
        params,
        requireNotNull(BackupWorkerDependencies.repository) {
            "BackupWorkerDependencies.repository must be set before scheduling backup work"
        },
    )

    override suspend fun doWork(): Result {
        val repo = repository
        val notification = buildNotification(0, 0)
        runCatching { setForeground(ForegroundInfo(NOTIFICATION_ID, notification)) }

        // Repair anything a previous process left mid-flight before touching new work.
        repo.recoverInterruptedWork()

        var uploaded = 0
        var processedThisRun = 0
        var waiting = false
        while (processedThisRun < MAX_ITEMS_PER_RUN) {
            if (isStopped) break
            val summary = repo.processPendingWork(maxItems = BATCH_SIZE)
            processedThisRun += summary.processed
            uploaded += summary.uploaded
            waiting = summary.hasRetryableWork
            updateNotification(uploaded, processedThisRun)
            if (summary.processed == 0) break // queue drained of actionable items
        }

        // If we only made progress by parking items to wait, ask WorkManager to retry later (backoff);
        // if we are out of actionable work, finish. A cancellation is not a failure of the queue.
        return if (isStopped) Result.success()
        else if (waiting) Result.retry()
        else Result.success()
    }

    private suspend fun updateNotification(uploaded: Int, processed: Int) {
        runCatching { setForeground(ForegroundInfo(NOTIFICATION_ID, buildNotification(uploaded, processed))) }
    }

    private fun buildNotification(uploaded: Int, processed: Int): Notification {
        ensureChannel(applicationContext)
        val text = if (processed > 0) {
            applicationContext.getString(R.string.backup_notification_progress, uploaded, processed)
        } else {
            applicationContext.getString(R.string.backup_notification_starting)
        }
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.backup_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_backup)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.backup_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 4201
        const val CHANNEL_ID = "telepix_backup"
        const val BATCH_SIZE = 5
        const val MAX_ITEMS_PER_RUN = 25
    }
}
