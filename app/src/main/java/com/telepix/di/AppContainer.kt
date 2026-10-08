package com.telepix.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.telepix.BuildConfig
import com.telepix.data.cloud.CloudDataSource
import com.telepix.data.cloud.CloudRepository
import com.telepix.data.cloud.TdLibCloudDataSource
import com.telepix.data.cloud.TelegramCloudRepository
import com.telepix.data.cloud.db.CloudMediaManifestDao
import com.telepix.data.cloud.db.CloudDestinationDao
import com.telepix.data.cloud.db.TelepixDatabase
import com.telepix.data.media.AndroidMediaChangeWatcher
import com.telepix.data.media.LocalMediaRepository
import com.telepix.data.media.LocalMediaRepositoryImpl
import com.telepix.data.media.MediaChangeWatcher
import com.telepix.data.media.MediaStoreMediaLoader
import com.telepix.onboarding.OnboardingRepository
import com.telepix.onboarding.OnboardingRepositoryImpl
import com.telepix.settings.SettingsRepository
import com.telepix.settings.SettingsRepositoryImpl
import com.telepix.telegram.KeystoreTdLibKeyProvider
import com.telepix.telegram.TelegramAuthController
import com.telepix.telegram.TelegramSessionManager
import com.telepix.telegram.TdLibClientGateway
import com.telepix.telegram.TdLibClientGatewayImpl
import com.telepix.telegram.TdLibTelegramAuthController
import java.io.File

/** Single DataStore instance for the whole process, shared by all preference repositories. */
private val Context.telepixDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "telepix_settings",
)

/**
 * Lightweight manual dependency container.
 *
 * Deliberately avoids a DI framework to keep the build simple; shaped so a future Hilt/Koin
 * migration swaps implementations without touching callers. A **single** TDLib gateway backs both
 * the Phase 4 session and the Phase 5 cloud layer — there is never a second Telegram client.
 */
interface AppContainer {
    val settingsRepository: SettingsRepository
    val onboardingRepository: OnboardingRepository
    val telegramAuthController: TelegramAuthController
    val cloudRepository: CloudRepository
    val localMediaRepository: LocalMediaRepository
    val mediaChangeWatcher: MediaChangeWatcher
}

class DefaultAppContainer(private val context: Context) : AppContainer {

    private val appContext = context.applicationContext

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(appContext.telepixDataStore)
    }

    override val onboardingRepository: OnboardingRepository by lazy {
        OnboardingRepositoryImpl(appContext.telepixDataStore)
    }

    private val database: TelepixDatabase by lazy {
        Room.databaseBuilder(appContext, TelepixDatabase::class.java, TelepixDatabase.NAME).build()
    }

    private val cloudDestinationDao: CloudDestinationDao by lazy { database.cloudDestinationDao() }
    private val cloudManifestDao: CloudMediaManifestDao by lazy { database.cloudMediaManifestDao() }

    // One shared TDLib client for both authentication and cloud.
    private val tdLibClientGateway: TdLibClientGateway by lazy { TdLibClientGatewayImpl() }

    private val telegramSessionManager: TelegramSessionManager by lazy {
        TelegramSessionManager(
            context = appContext,
            gateway = tdLibClientGateway,
            keyProvider = KeystoreTdLibKeyProvider(appContext),
            apiId = BuildConfig.TELEGRAM_API_ID,
            apiHash = BuildConfig.TELEGRAM_API_HASH,
            applicationVersion = BuildConfig.VERSION_NAME,
        )
    }

    override val telegramAuthController: TelegramAuthController by lazy {
        TdLibTelegramAuthController(telegramSessionManager)
    }

    private val cloudDataSource: CloudDataSource by lazy {
        val downloads = File(appContext.noBackupFilesDir, "telepix_cloud").apply { mkdirs() }
        TdLibCloudDataSource(
            filesDir = downloads,
            authState = telegramSessionManager.state,
        )
    }

    override val cloudRepository: CloudRepository by lazy {
        TelegramCloudRepository(
            dataSource = cloudDataSource,
            destinationDao = cloudDestinationDao,
            manifestDao = cloudManifestDao,
            authState = telegramSessionManager.state,
        )
    }

    override val localMediaRepository: LocalMediaRepository by lazy {
        LocalMediaRepositoryImpl(MediaStoreMediaLoader(appContext))
    }

    override val mediaChangeWatcher: MediaChangeWatcher by lazy {
        AndroidMediaChangeWatcher(appContext)
    }
}
