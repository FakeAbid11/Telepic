package com.telepic.data.backup.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.telepic.R
import com.telepic.data.backup.BackupRepository
import com.telepic.diagnostics.TelepicLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * The single backup [CoroutineWorker]. It never depends on an Activity, Composable, or screen being
 * alive. It runs the resumable queue loop off the main thread, reports an honest foreground
 * notification ("Preparing your backup…", then "Uploading n of m" for this run) that is raised before
 * the upload pass whenever the queue has work, and cooperatively stops on cancel.
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

    /**
     * Reflective constructor. WorkManager only reaches it if no factory is configured, which cannot
     * happen here: the manifest removes WorkManager's default initializer and
     * [com.telepic.TelepicApplication] supplies [TelepicWorkerFactory], so the factory always passes
     * the repository. It used to read a process-global the container installed as a side effect of
     * lazy initialisation, which made construction depend on initialisation order — and forced the
     * tests to null that global to avoid the resulting race.
     */
    constructor(appContext: Context, params: WorkerParameters) : this(
        appContext,
        params,
        error("BackupWorker must be created by TelepicWorkerFactory"),
    )

    override suspend fun doWork(): Result =
        try {
            runPass()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // A run can fail for a reason no branch below models — a database error, a provider throw.
            // That is not provably permanent, so ask WorkManager to retry (with its backoff) instead of
            // letting the exception turn the run into a permanent FAILED one. Nothing is claimed as
            // backed up on this path: the queue rows keep whatever state they already had.
            TelepicLog.error("BackupWorker", "backup run failed unexpectedly; asking WorkManager to retry", throwable)
            // Make the failure durable. A row this pass had already advanced to PREPARING/UPLOADING
            // would otherwise stay mid-flight, so the Backup Center would keep showing it as
            // "Uploading" until the *next* run's own recovery sweep — which may be a whole backoff
            // period away. Returning those rows to QUEUED now keeps the persisted state honest about
            // what actually happened. Best-effort: a failure here must not replace the retry.
            runCatching { repository.recoverInterruptedWork() }
                .onFailure {
                    TelepicLog.warn("BackupWorker", "could not recover interrupted rows after a failed run", it)
                }
            Result.retry()
        }

    private suspend fun runPass(): Result {
        val repo = repository

        // Repair anything a previous process left mid-flight before touching new work.
        repo.recoverInterruptedWork()

        // Promote to foreground BEFORE the upload pass, and only when the queue actually has
        // something to do: the notification has to cover the slow part (TDLib sends), and an empty
        // tick must not flash a "Preparing your backup…" line every 12 hours. It stays best-effort —
        // a denied notification permission or an Android 14 background-FGS refusal is swallowed here,
        // the upload itself continues, and the Backup Center remains the in-app source of truth.
        val pending = runCatching { repo.observeStats().first() }
            .getOrNull()
            ?.let { it.queued + it.uploading }
            ?: 0
        if (pending > 0) {
            runCatching { setForeground(ForegroundInfo(NOTIFICATION_ID, buildNotification(uploaded = 0, processed = 0))) }
                .onFailure {
                    // Best-effort by design, but it used to be invisible: a denied notification
                    // permission or an Android 14 background-FGS refusal left the run with no
                    // "backup in progress" affordance anywhere outside the app.
                    TelepicLog.warn("BackupWorker", "foreground notification unavailable; the upload run continues", it)
                }
        }

        // One pass over the queue: processPendingWork attempts each actionable item at most once, so
        // the retry budget of a transiently failing item cannot be burned repeatedly inside a single
        // run — WorkManager's backoff, not a tighter loop here, provides the retry delay. State is
        // aggregated from that one summary, so an empty tail can never overwrite an earlier waiting
        // condition and silently report success.
        val summary = repo.processPendingWork(maxItems = MAX_ITEMS_PER_RUN)

        if (summary.processed > 0) {
            // Same notification id, so this updates the in-place foreground line with real counts
            // rather than posting a second one WorkManager does not own.
            runCatching { setForeground(ForegroundInfo(NOTIFICATION_ID, buildNotification(summary.uploaded, summary.processed))) }
                .onFailure { TelepicLog.warn("BackupWorker", "could not update the backup notification", it) }
        }

        // Nothing a re-run can fix: the rows are parked because there is no Telegram session to upload
        // to, and `markWaiting` deliberately spends no retry budget on them so they resume intact.
        // Returning success here instead of retrying ends what was previously an unbounded loop — every
        // backoff period, forever, each time with a foreground-service notification. What resumes the
        // queue instead is the session actually coming back (see `TelepicApplication`), a new item being
        // discovered, or the user pressing Start in the Backup Center.
        if (summary.blockedOnAuth) {
            TelepicLog.warn("BackupWorker", "${summary.waitingForAuth} item(s) parked: no Telegram session to upload to")
            return Result.success()
        }

        // Retryable (waiting) work asks WorkManager to run again after backoff; a drained or purely
        // permanently-failed queue is a normal success. (A stop request cancels this coroutine and
        // WorkManager discards whatever result it would have returned — there is no `isStopped`
        // check to make here.)
        //
        // The second condition is what makes a library longer than one batch actually finish. A
        // batch that filled up leaves rows still QUEUED, and **nothing else re-schedules them**: the
        // 12h discovery tick only calls `schedule()` when it enqueues *new* media
        // (BackupCoordinator.syncFromPreference), and re-scanning an already-queued library enqueues
        // nothing — every item resolves to Pending, not queued. Reporting success here therefore
        // ended a first backup after one batch and left the rest waiting for the user to open the
        // Backup Center. Asking for another run instead keeps the queue draining unattended.
        val stillActive = runCatching { repo.observeStats().first() }
            .getOrNull()
            ?.activeCount
            ?: 0
        return if (summary.hasRetryableWork || stillActive > 0) Result.retry() else Result.success()
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
        const val CHANNEL_ID = "telepic_backup"

        /**
         * Upload attempts per run. Deliberately generous: `processPendingWork` already attempts each
         * actionable item **at most once** per call (its own `attempted` set), so a larger batch
         * cannot burn a transiently-failing item's retry budget — the reason the batch is bounded at
         * all is run length, not retry safety. At 25, a library of a few hundred items needed dozens
         * of runs and the retry backoff grew past the point of usefulness between them.
         */
        const val MAX_ITEMS_PER_RUN = 100
    }
}
