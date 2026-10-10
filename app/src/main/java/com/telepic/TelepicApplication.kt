package com.telepic

import android.app.Application
import android.os.Build
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.decode.GifDecoder
import com.telepic.data.backup.work.TelepicWorkerFactory
import com.telepic.di.AppContainer
import com.telepic.di.DefaultAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        // Initialize the Telegram session at startup to restore a persisted login. This opens
        // TDLib on a background scope and never blocks or crashes local startup (Photos works
        // regardless); failures surface as a Telegram error state, not an app crash.
        container.telegramAuthController.start()
        // Honor the persisted backup preference off the main thread: enqueue eligible media when
        // the user chose BACKUP_ALL and schedule a network-constrained run. No-op for NOT_NOW.
        appScope.launch { container.backupCoordinator.syncFromPreference() }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(TelepicWorkerFactory { container.backupRepository })
            .build()

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
