package com.telepic.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.telepic.BuildConfig
import com.telepic.data.backup.BackupCoordinator
import com.telepic.data.backup.BackupRecognitionRepository
import com.telepic.data.backup.BackupRepository
import com.telepic.data.backup.BackupStager
import com.telepic.data.backup.MediaStoreBackupStager
import com.telepic.data.backup.BackupStatusRepository
import com.telepic.data.backup.DefaultBackupCoordinator
import com.telepic.data.backup.DefaultBackupRecognitionRepository
import com.telepic.data.backup.DefaultBackupRepository
import com.telepic.data.backup.DefaultBackupStatusRepository
import com.telepic.data.backup.hash.AndroidContentHasher
import com.telepic.data.backup.hash.ContentHasher
import com.telepic.data.backup.work.BackupWorkScheduler
import com.telepic.data.backup.work.BackupWorkerDependencies
import com.telepic.data.backup.work.WorkManagerBackupScheduler
import com.telepic.data.cloud.CloudDataSource
import com.telepic.data.cloud.CloudRepository
import com.telepic.data.cloud.TdLibCloudDataSource
import com.telepic.data.cloud.TelegramCloudRepository
import com.telepic.data.cloud.db.CloudMediaManifestDao
import com.telepic.data.cloud.db.CloudDestinationDao
import com.telepic.data.cloud.db.TelepicDatabase
import com.telepic.data.media.AndroidMediaChangeWatcher
import com.telepic.data.media.AndroidLocationExtractor
import com.telepic.data.media.AlbumRepository
import com.telepic.data.media.DefaultMapLocationRepository
import com.telepic.data.media.MapLocationRepository
import com.telepic.data.media.MediaMetadataReader
import com.telepic.data.media.AndroidMediaMetadataReader
import com.telepic.data.media.LocalMediaLookup
import com.telepic.data.media.LocalMediaRepository
import com.telepic.data.media.LocalMediaRepositoryImpl
import com.telepic.data.media.MediaChangeWatcher
import com.telepic.data.media.MediaStoreAlbumRepository
import com.telepic.data.media.MediaStoreLocalLookup
import com.telepic.data.media.MediaStoreBucketLoader
import com.telepic.data.media.MediaStoreMediaLoader
import com.telepic.data.organization.DefaultMediaOrganizationRepository
import com.telepic.data.organization.LocalMediaDeleter
import com.telepic.data.organization.MediaOrganizationRepository
import com.telepic.data.organization.MediaStoreLocalDeleter
import com.telepic.data.restore.AndroidMediaStorePublisher
import com.telepic.data.restore.DefaultRestoreRepository
import com.telepic.data.restore.RestoreRepository
import com.telepic.onboarding.OnboardingRepository
import com.telepic.onboarding.OnboardingRepositoryImpl
import com.telepic.settings.SettingsRepository
import com.telepic.settings.SettingsRepositoryImpl
import com.telepic.telegram.KeystoreTdLibKeyProvider
import com.telepic.telegram.TelegramAuthController
import com.telepic.telegram.TelegramSessionManager
import com.telepic.telegram.TdLibClientGateway
import com.telepic.telegram.TdLibClientGatewayImpl
import com.telepic.telegram.TdLibTelegramAuthController
import java.io.File

/** Single DataStore instance for the whole process, shared by all preference repositories. */
private val Context.telepicDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "telepic_settings",
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
    val mediaMetadataReader: MediaMetadataReader
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
        SettingsRepositoryImpl(appContext.telepicDataStore)
    }

    override val onboardingRepository: OnboardingRepository by lazy {
        OnboardingRepositoryImpl(appContext.telepicDataStore)
    }

    private val database: TelepicDatabase by lazy {
        Room.databaseBuilder(appContext, TelepicDatabase::class.java, TelepicDatabase.NAME)
            .addMigrations(
                TelepicDatabase.MIGRATION_1_2,
                TelepicDatabase.MIGRATION_2_3,
                TelepicDatabase.MIGRATION_3_4,
                TelepicDatabase.MIGRATION_4_5,
                TelepicDatabase.MIGRATION_5_6,
            )
            .build()
    }

    private val cloudDestinationDao: CloudDestinationDao by lazy { database.cloudDestinationDao() }
    private val cloudManifestDao: CloudMediaManifestDao by lazy { database.cloudMediaManifestDao() }
    private val backupQueueDao: com.telepic.data.backup.db.BackupQueueDao by lazy { database.backupQueueDao() }
    private val mediaOrganizationDao: com.telepic.data.organization.db.MediaOrganizationDao by lazy { database.mediaOrganizationDao() }

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

    private val mediaLocationDao: com.telepic.data.media.db.MediaLocationDao by lazy { database.mediaLocationDao() }

    override val mapLocationRepository: MapLocationRepository by lazy {
        DefaultMapLocationRepository(
            dao = mediaLocationDao,
            pageLoader = mediaLoader,
            extractor = AndroidLocationExtractor(appContext),
        )
    }

    override val mediaMetadataReader: MediaMetadataReader by lazy {
        AndroidMediaMetadataReader(appContext)
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
        val staging = File(appContext.cacheDir, "telepic_backup_staging").apply { mkdirs() }
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
        val coordinator = DefaultBackupCoordinator(
            repository = backupRepository,
            recognition = backupRecognitionRepository,
            scheduler = backupWorkScheduler,
            onboardingRepository = onboardingRepository,
            // null bucket = whole library; a bucket id = the same projection scoped to that folder,
            // so SELECT_FOLDER discovery reads exactly what the user chose.
            pageLoaderFactory = { bucketId ->
                if (bucketId == null) {
                    mediaLoader
                } else {
                    MediaStoreBucketLoader(appContext, bucketId) { mediaOrganizationRepository.hiddenIds() }
                }
            },
        )
        // The periodic discovery worker is created by WorkManager, not here; install the seam
        // after construction (touching backupWorkScheduler first would re-enter this lazy block).
        BackupWorkerDependencies.coordinator = coordinator
        coordinator
    }
}
