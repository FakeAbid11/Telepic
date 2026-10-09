package com.telepix.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.telepix.BuildConfig
import com.telepix.data.backup.BackupCoordinator
import com.telepix.data.backup.BackupRecognitionRepository
import com.telepix.data.backup.BackupRepository
import com.telepix.data.backup.BackupStager
import com.telepix.data.backup.MediaStoreBackupStager
import com.telepix.data.backup.BackupStatusRepository
import com.telepix.data.backup.DefaultBackupCoordinator
import com.telepix.data.backup.DefaultBackupRecognitionRepository
import com.telepix.data.backup.DefaultBackupRepository
import com.telepix.data.backup.DefaultBackupStatusRepository
import com.telepix.data.backup.hash.AndroidContentHasher
import com.telepix.data.backup.hash.ContentHasher
import com.telepix.data.backup.work.BackupWorkScheduler
import com.telepix.data.backup.work.BackupWorkerDependencies
import com.telepix.data.backup.work.WorkManagerBackupScheduler
import com.telepix.data.cloud.CloudDataSource
import com.telepix.data.cloud.CloudRepository
import com.telepix.data.cloud.TdLibCloudDataSource
import com.telepix.data.cloud.TelegramCloudRepository
import com.telepix.data.cloud.db.CloudMediaManifestDao
import com.telepix.data.cloud.db.CloudDestinationDao
import com.telepix.data.cloud.db.TelepixDatabase
import com.telepix.data.media.AndroidMediaChangeWatcher
import com.telepix.data.media.AndroidLocationExtractor
import com.telepix.data.media.AlbumRepository
import com.telepix.data.media.DefaultMapLocationRepository
import com.telepix.data.media.MapLocationRepository
import com.telepix.data.media.LocalMediaLookup
import com.telepix.data.media.LocalMediaRepository
import com.telepix.data.media.LocalMediaRepositoryImpl
import com.telepix.data.media.MediaChangeWatcher
import com.telepix.data.media.MediaStoreAlbumRepository
import com.telepix.data.media.MediaStoreLocalLookup
import com.telepix.data.media.MediaStoreMediaLoader
import com.telepix.data.organization.DefaultMediaOrganizationRepository
import com.telepix.data.organization.LocalMediaDeleter
import com.telepix.data.organization.MediaOrganizationRepository
import com.telepix.data.organization.MediaStoreLocalDeleter
import com.telepix.data.restore.AndroidMediaStorePublisher
import com.telepix.data.restore.DefaultRestoreRepository
import com.telepix.data.restore.RestoreRepository
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
    val localMediaLookup: LocalMediaLookup
    val albumRepository: AlbumRepository
    val mediaOrganizationRepository: MediaOrganizationRepository
    val mediaDeleter: LocalMediaDeleter
    val mapLocationRepository: MapLocationRepository
    val restoreRepository: RestoreRepository
    val mediaChangeWatcher: MediaChangeWatcher
    val backupRepository: BackupRepository
    val backupStatusRepository: BackupStatusRepository
    val backupCoordinator: BackupCoordinator
    val backupWorkScheduler: BackupWorkScheduler
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
        Room.databaseBuilder(appContext, TelepixDatabase::class.java, TelepixDatabase.NAME)
            .addMigrations(
                TelepixDatabase.MIGRATION_1_2,
                TelepixDatabase.MIGRATION_2_3,
                TelepixDatabase.MIGRATION_3_4,
                TelepixDatabase.MIGRATION_4_5,
            )
            .build()
    }

    private val cloudDestinationDao: CloudDestinationDao by lazy { database.cloudDestinationDao() }
    private val cloudManifestDao: CloudMediaManifestDao by lazy { database.cloudMediaManifestDao() }
    private val backupQueueDao: com.telepix.data.backup.db.BackupQueueDao by lazy { database.backupQueueDao() }
    private val mediaOrganizationDao: com.telepix.data.organization.db.MediaOrganizationDao by lazy { database.mediaOrganizationDao() }

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
        TdLibCloudDataSource(
            gateway = tdLibClientGateway,
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

    private val mediaLoader: MediaStoreMediaLoader by lazy {
        MediaStoreMediaLoader(appContext) { mediaOrganizationRepository.hiddenIds() }
    }

    override val localMediaRepository: LocalMediaRepository by lazy {
        LocalMediaRepositoryImpl(mediaLoader)
    }

    override val localMediaLookup: LocalMediaLookup by lazy {
        MediaStoreLocalLookup(appContext) { mediaOrganizationRepository.hiddenIds() }
    }

    override val mediaOrganizationRepository: MediaOrganizationRepository by lazy {
        DefaultMediaOrganizationRepository(mediaOrganizationDao)
    }

    override val mediaDeleter: LocalMediaDeleter by lazy { MediaStoreLocalDeleter(appContext) }

    private val mediaLocationDao: com.telepix.data.media.db.MediaLocationDao by lazy { database.mediaLocationDao() }

    override val mapLocationRepository: MapLocationRepository by lazy {
        DefaultMapLocationRepository(
            dao = mediaLocationDao,
            pageLoader = mediaLoader,
            extractor = AndroidLocationExtractor(appContext),
        )
    }

    override val restoreRepository: RestoreRepository by lazy {
        DefaultRestoreRepository(
            cloudRepository = cloudRepository,
            publisher = AndroidMediaStorePublisher(appContext),
        )
    }

    override val albumRepository: AlbumRepository by lazy {
        MediaStoreAlbumRepository(appContext) { mediaOrganizationRepository.hiddenIds() }
    }

    override val mediaChangeWatcher: MediaChangeWatcher by lazy {
        AndroidMediaChangeWatcher(appContext)
    }

    private val backupStager: BackupStager by lazy {
        val staging = File(appContext.cacheDir, "telepix_backup_staging").apply { mkdirs() }
        MediaStoreBackupStager(appContext.contentResolver, staging)
    }

    private val contentHasher: ContentHasher by lazy { AndroidContentHasher(appContext.contentResolver) }

    private val backupRecognitionRepository: BackupRecognitionRepository by lazy {
        DefaultBackupRecognitionRepository(
            queueDao = backupQueueDao,
            manifestDao = cloudManifestDao,
            hasher = contentHasher,
        )
    }

    override val backupRepository: BackupRepository by lazy {
        DefaultBackupRepository(
            dao = backupQueueDao,
            cloudRepository = cloudRepository,
            authState = telegramSessionManager.state,
            stager = backupStager,
        )
    }

    override val backupStatusRepository: BackupStatusRepository by lazy {
        DefaultBackupStatusRepository(backupQueueDao)
    }

    override val backupWorkScheduler: BackupWorkScheduler by lazy {
        // The worker is created by WorkManager, not here, so hand it the repository up front.
        BackupWorkerDependencies.repository = backupRepository
        WorkManagerBackupScheduler(appContext)
    }

    override val backupCoordinator: BackupCoordinator by lazy {
        DefaultBackupCoordinator(
            repository = backupRepository,
            recognition = backupRecognitionRepository,
            scheduler = backupWorkScheduler,
            onboardingRepository = onboardingRepository,
            pageLoader = mediaLoader,
        )
    }
}
