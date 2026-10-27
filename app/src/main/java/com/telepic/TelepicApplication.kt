package com.telepic

import android.app.Application
import android.os.Build
import android.os.Process
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.decode.GifDecoder
import com.telepic.data.backup.work.TelepicWorkerFactory
import com.telepic.di.AppContainer
import com.telepic.di.DefaultAppContainer
import com.telepic.diagnostics.TelepicLog
import com.telepic.map.OsmdroidConfiguration
import com.telepic.telegram.TelegramAuthState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Application entrypoint. Owns the manual [AppContainer], configures the app-wide Coil
 * [ImageLoader], starts the Telegram session, and initializes WorkManager on demand with a
 * [TelepicWorkerFactory] so the backup worker can reach the container's repositories without a DI
 * framework.
 */
class TelepicApplication : Application(), ImageLoaderFactory, Configuration.Provider {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            // Background work (the Telegram state collection, the startup backup sync) must never be
            // able to take the process down. Each call site is guarded in its own right; this is the
            // net for the one that is not, and it reports rather than swallowing.
            TelepicLog.error("AppScope", "unhandled failure in a background coroutine", throwable)
        },
    )

    override fun onCreate() {
        super.onCreate()
        TelepicLog.initialize(this)
        installCrashLogger()
        // Configure osmdroid once, before any map exists, so its User-Agent and tile cache are the
        // app's and not the library defaults.
        OsmdroidConfiguration.initialize(this)
        // The CARTO key migration renamed the tile sources (V2), orphaning the old cache. Reclaim it
        // without touching the main thread; the directories stop existing after the first run.
        appScope.launch(Dispatchers.IO) {
            OsmdroidConfiguration.deleteRetiredTileCaches(
                OsmdroidConfiguration.tileCacheRoot(filesDir),
            )
        }
        container = DefaultAppContainer(this)
        // Initialize the Telegram session at startup to restore a persisted login. This opens
        // TDLib on a background scope and never blocks or crashes local startup (Photos works
        // regardless); failures surface as a Telegram error state, not an app crash.
        container.telegramAuthController.start()
        // Honor the persisted backup preference off the main thread: enqueue eligible media when
        // the user chose BACKUP_ALL and schedule a network-constrained run. No-op for NOT_NOW.
        appScope.launch { container.backupCoordinator.syncFromPreference() }
        // Remove the staged copies a killed upload left behind. The per-attempt cleanup lives in a
        // `finally` that a process death skips, so a full-size copy of the media could sit in the
        // cache directory forever. The sweep only touches files no upload can still be using.
        appScope.launch {
            try {
                container.sweepBackupStaging()
            } catch (throwable: Throwable) {
                TelepicLog.warn("BackupStaging", "could not sweep the staging directory", throwable)
            }
        }
        // What resumes an auth-parked queue. [BackupWorker] deliberately stops retrying when its only
        // outcome was "there is no Telegram session", so the session coming back has to hand the parked
        // rows over itself — otherwise signing in later would leave them waiting for the next 12h
        // discovery tick, which never schedules a run for media it did not newly enqueue.
        appScope.launch {
            container.telegramAuthController.state
                .filter { it is TelegramAuthState.Authorized }
                .collect { state ->
                    val accountId = (state as TelegramAuthState.Authorized).user.id
                    // Scope persisted cloud/queue/restore state before handing work to the queue.
                    // If this fails, uploading would target a destination that belongs to a
                    // different account, so the queue is left parked until the next authorized
                    // emission rather than risking a wrong-account upload.
                    val reconciled = runCatching { container.reconcileTelegramAccount(accountId) }
                        .onFailure {
                            TelepicLog.error(
                                "TelegramAccount",
                                "could not scope persisted state to the signed-in account",
                                it,
                            )
                        }
                        .isSuccess
                    if (!reconciled) return@collect
                    runCatching { container.backupCoordinator.startPendingBackup() }
                        .onFailure { TelepicLog.error("BackupScheduler", "could not resume the queue after sign-in", it) }
                }
        }
        // One-time map backfill. Android redacts the GPS in every MediaStore open on API 29+ until the
        // app holds ACCESS_MEDIA_LOCATION, so the map's scan memory recorded genuinely geotagged files
        // as "examined, no GPS". The first grant has to invalidate that memory once, or an existing
        // install would keep an empty map forever. App-scoped (never a screen) so it also covers the
        // case where the permission is already granted when the process starts. A failure leaves the
        // persisted flag unset, so the next launch tries again rather than losing the repair silently.
        appScope.launch {
            container.mediaPermissionMonitor.hasAccess.collect { hasAccess ->
                runCatching { container.mediaLocationBackfill.onMediaAccessChanged(hasAccess) }
                    .onFailure { TelepicLog.warn("MediaLocationBackfill", "could not invalidate the map scan memory", it) }
            }
        }
    }

    /**
     * Turns the app's signature failure mode — a silent process death — into a stack trace.
     *
     * Every coroutine boundary here degrades instead of rethrowing, and `viewModelScope` installs no
     * `CoroutineExceptionHandler`, so when an exception still escapes there is nothing in logcat and
     * nothing on disk: the app just closes. (That is exactly how the "choosing a folder closes the
     * app" report presented, and why it had to be diagnosed by reading code rather than by reading a
     * crash.) This installs one handler that logs the throwable through [TelepicLog] and then hands
     * off to the platform's handler, so the process still fails fast exactly as before — but
     * `adb logcat -b crash` finally has the reason.
     *
     * It must never swallow: the previous handler is always invoked when present, and the process is
     * killed directly when it is not, so an uncaught error can never leave a half-dead process behind.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // A crash inside the crash logger must not mask the original throwable.
            runCatching { TelepicLog.error("Crash", "uncaught exception on thread ${thread.name}", throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    // `by lazy`, not a `get()` accessor: WorkManager reads this configuration during its own lazy
    // init, and a fresh `Configuration`/factory/lambda set was being built on every access. A single
    // instance is also the honest contract — WorkManager expects one stable configuration, and a
    // re-read (after a process restart or configuration change) must not observe a different object
    // whose lambdas capture a not-yet-initialised container.
    override val workManagerConfiguration: Configuration by lazy {
        Configuration.Builder()
            .setWorkerFactory(
                TelepicWorkerFactory(
                    backupRepository = { container.backupRepository },
                    backupCoordinator = { container.backupCoordinator },
                    // The worker hands the remainder of a long drain to a fresh run through this, so a
                    // big library is not throttled by WorkManager's exponential backoff between
                    // batches.
                    backupScheduler = { container.backupWorkScheduler },
                ),
            )
            .build()
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                // Animated GIFs: ImageDecoderDecoder (which requires API 28) on newer devices, and
                // Coil's GifDecoder as the fallback that also covers API 26–27, so GIFs animate in the
                // grid and Viewer across the whole minSdk range. Registering ImageDecoderDecoder below
                // 28 would be an unsupported-API call, so it is version-gated.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(ImageDecoderDecoder.Factory())
                add(GifDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
}
