package com.telepic.data.backup.work

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
import com.telepic.data.backup.BackupProcessSummary
import com.telepic.data.backup.BackupRepository
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.backup.BackupQueueStats
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
    /**
     * Used only to hand the remainder of a long drain to a fresh run.
     *
     * Optional so the worker can be built without one (the tests do); when it is missing the worker
     * falls back to `Result.retry()`, which is correct but slower.
     */
    private val scheduler: BackupWorkScheduler? = null,
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
            recoverInterruptedRows()
            Result.retry()
        }

    /**
     * Return rows a failed run left mid-flight to QUEUED.
     *
     * Cancellation is rethrown rather than swallowed: a stopped worker must stop, and the
     * `runCatching` that used to wrap this call turned a stop into a silent "the recovery failed"
     * while the worker carried on uploading.
     */
    private suspend fun recoverInterruptedRows() {
        try {
            repository.recoverInterruptedWork()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            TelepicLog.warn("BackupWorker", "could not recover interrupted rows after a failed run", throwable)
        }
    }

    /**
     * The queue's current counts, or null when they cannot be read.
     *
     * A cancelled read is rethrown: the worker's scope is going away and a fallback of "zero"
     * would silently skip the foreground promotion.
     */
    private suspend fun readStats(): BackupQueueStats? =
        try {
            repository.observeStats().first()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            TelepicLog.warn("BackupWorker", "the queue counts could not be read", throwable)
            null
        }

    /**
     * Promote the worker to the foreground, best-effort.
     *
     * A denied notification permission or an Android 14 background-FGS refusal must not fail the
     * upload — but it used to be invisible, which left the run with no "backup in progress"
     * affordance anywhere outside the app. Cancellation is rethrown: a stop request has to stop
     * the worker, and swallowing it made the run carry on uploading after being told to stop.
     */
    private suspend fun setForegroundSafely(notification: Notification) {
        try {
            setForeground(foregroundInfo(notification))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            TelepicLog.warn("BackupWorker", "foreground notification unavailable; the upload run continues", throwable)
        }
    }

    private suspend fun runPass(): Result {
        val repo = repository

        // Create the notification channel once per run, not on every foreground update: ensureChannel
        // does a `getNotificationChannel` binder round-trip and buildNotification is called on each
        // progress tick. Per-run (rather than Application.onCreate) keeps it exercised by
        // BackupWorkerTest's TestListenableWorkerBuilder lifecycle.
        ensureChannel(applicationContext)

        // Repair anything a previous process left mid-flight before touching new work.
        repo.recoverInterruptedWork()

        // Promote to foreground BEFORE the upload pass, and only when the queue actually has
        // something to do: the notification has to cover the slow part (TDLib sends), and an empty
        // tick must not flash a "Preparing your backup…" line every 12 hours.
        val pending = readStats()?.let { it.queued + it.uploading } ?: 0
        if (pending > 0) {
            setForegroundSafely(buildNotification(uploaded = 0, processed = 0))
        }

        // Drain the queue in batches **inside this run**.
        //
        // The alternative — returning `Result.retry()` and letting WorkManager re-run the same
        // WorkSpec — inherits WorkManager's exponential backoff, which grows 10s → 20s → 40s → …
        // and pins at the five-hour cap. A ten-thousand-item library needs a hundred batches, so
        // the drain took about *twenty days*. The backoff is for an item that keeps failing, not
        // for a queue that simply has more items than one batch holds.
        var total = BackupProcessSummary()
        var batches = 0
        while (true) {
            val summary = repo.processPendingWork(maxItems = MAX_ITEMS_PER_RUN)
            total += summary
            if (summary.processed > 0) {
                setForegroundSafely(buildNotification(uploaded = total.uploaded, processed = total.processed))
            }
            if (summary.blockedOnAuth) {
                // Nothing a re-run can fix: the rows are parked because there is no Telegram session
                // to upload to, and `markWaiting` deliberately spends no retry budget on them so they
                // resume intact. Returning success here instead of retrying ends what was previously
                // an unbounded loop — every backoff period, forever, each time with a
                // foreground-service notification. What resumes the queue instead is the session
                // actually coming back (see `TelepicApplication`), a new item being discovered, or
                // the user pressing Start in the Backup Center.
                TelepicLog.warn("BackupWorker", "${total.waitingForAuth} item(s) parked: no Telegram session to upload to")
                return Result.success()
            }
            // This batch found nothing actionable: the drain is complete.
            if (summary.processed == 0) break
            batches++
            if (batches >= MAX_BATCHES_PER_RUN) {
                // The batch cap was reached with work still queued. The rest goes to a fresh run
                // rather than inheriting this run's (now large) backoff.
                TelepicLog.warn(
                    "BackupWorker",
                    "batch cap reached with work still queued; handing the remainder to a fresh run",
                )
                return continueInFreshRun()
            }
        }

        // Retryable (waiting) work asks WorkManager to run again after backoff — and this is the
        // case the backoff is *for*: an item that failed for a transient reason. A drained or
        // purely permanently-failed queue is a normal success. (A stop request cancels this
        // coroutine and WorkManager discards whatever result it would have returned — there is no
        // `isStopped` check to make here.)
        if (total.hasRetryableWork) return Result.retry()
        return Result.success()
    }

    /**
     * Hand the remainder of the queue to a fresh run.
     *
     * Without a scheduler the only thing left is `Result.retry()`, which is correct but slow: the
     * next run inherits this one's backoff. With one, a continuation is a new spec and starts fresh.
     */
    private suspend fun continueInFreshRun(): Result {
        val scheduler = scheduler ?: return Result.retry()
        return try {
            scheduler.scheduleContinuation()
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            TelepicLog.warn("BackupWorker", "could not schedule the next batch; asking WorkManager to retry", throwable)
            Result.retry()
        }
    }

    private fun buildNotification(uploaded: Int, processed: Int): Notification {
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

    /**
     * A foreground notification carrying the service type WorkManager must pass to
     * `startForeground` on Android 10+. WorkManager's own `SystemForegroundService` is declared
     * without a `foregroundServiceType`, and since targetSdk 34 a foreground service started as
     * type "none" is rejected outright — `InvalidForegroundServiceTypeException`, which killed the
     * process the moment a backup began. `dataSync` is the correct type for an upload and matches
     * the `FOREGROUND_SERVICE_DATA_SYNC` permission and the manifest's service override.
     */
    private fun foregroundInfo(notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
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
         * actionable item **at most once** per call, so a larger batch cannot burn a
         * transiently-failing item's retry budget — the reason the batch is bounded at all is run
         * length, not retry safety.
         */
        const val MAX_ITEMS_PER_RUN = 100

        /**
         * Batches drained inside a single run.
         *
         * A run is a foreground job, so it may take as long as the uploading takes; the cap is
         * only there to bound one foreground notification's lifetime. When it is reached the rest of
         * the queue goes to a fresh run (see `continueInFreshRun`), so a long library still
         * finishes — without any of it waiting on an exponential backoff.
         */
        const val MAX_BATCHES_PER_RUN = 20
    }
}
