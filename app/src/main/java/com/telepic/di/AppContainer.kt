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
import com.telepic.data.PreferencesScanCursorStore
import com.telepic.data.backup.DefaultBackupCoordinator
import com.telepic.data.backup.DefaultBackupRecognitionRepository
import com.telepic.data.backup.DefaultBackupRepository
import com.telepic.data.backup.DefaultBackupStatusRepository
import com.telepic.data.backup.hash.AndroidContentHasher
import com.telepic.data.backup.hash.ContentHasher
import com.telepic.data.backup.work.BackupWorkScheduler
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
import com.telepic.data.media.CustomAlbumRepository
import com.telepic.data.media.DefaultCustomAlbumRepository
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
import com.telepic.data.media.TimelineMediaFilter
import com.telepic.data.organization.DefaultMediaOrganizationRepository
import com.telepic.data.organization.LocalMediaDeleter
import com.telepic.data.organization.MediaOrganizationRepository
import com.telepic.data.organization.MediaStoreLocalDeleter
import com.telepic.data.restore.AndroidMediaStorePublisher
import com.telepic.data.restore.DataStoreRestoreLedger
import com.telepic.data.restore.DefaultRestoreRepository
import com.telepic.data.restore.RestoreRepository
import com.telepic.onboarding.OnboardingRepository
import com.telepic.onboarding.OnboardingRepositoryImpl
import com.telepic.settings.MapCameraRepository
import com.telepic.settings.MapCameraRepositoryImpl
import com.telepic.settings.SettingsRepository
import com.telepic.settings.SettingsRepositoryImpl
import com.telepic.telegram.KeystoreTdLibKeyProvider
import com.telepic.telegram.TelegramAuthController
import com.telepic.telegram.TelegramSessionManager
import com.telepic.telegram.TdLibClientGateway
import com.telepic.telegram.TdLibClientGatewayImpl
import com.telepic.telegram.TdLibTelegramAuthController
import java.io.File
import kotlinx.coroutines.flow.first

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

    /**
     * The same MediaStore buckets, scoped to what a backup would actually take.
     *
     * [albumRepository] is a *display* view: it hides videos/GIFs and archived media, because the
     * Albums screen must describe what the timeline shows. Feeding that into the SELECT_FOLDER picker
     * made the choice dishonest in two ways — a folder holding only videos vanished from the list
     * entirely when the user hid videos, and the count beside a folder disagreed with the number of
     * items `BackupCoordinator` would discover in that bucket. This instance mirrors
     * `backupDiscoveryLoader`: trashed media excluded (it is on its way out), archived media included,
     * no display filter. Use it wherever a folder is chosen *for backup*.
     */
    val backupAlbumRepository: AlbumRepository
    val customAlbumRepository: CustomAlbumRepository
    val mediaOrganizationRepository: MediaOrganizationRepository
    val mediaDeleter: LocalMediaDeleter
    val mapLocationRepository: MapLocationRepository
    val mapCameraRepository: MapCameraRepository
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

    /**
     * The user's hide-videos / hide-GIFs preferences, read per query.
     *
     * Shared by every *library-view* loader (timeline, album contents, album grouping, Viewer
     * neighbour walk) so all of them agree about what the library contains. It is deliberately NOT
     * given to [backupDiscoveryLoader] or to the SELECT_FOLDER discovery loaders: what gets backed up
     * is decided by the backup preference, and a display filter must never silently shrink a backup
     * the user asked for.
     */
    private val timelineFilterProvider: suspend () -> TimelineMediaFilter = {
        TimelineMediaFilter(
            showVideos = settingsRepository.showVideosInTimeline.first(),
            showGifs = settingsRepository.showGifsInTimeline.first(),
        )
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
                TelepicDatabase.MIGRATION_6_7,
                TelepicDatabase.MIGRATION_7_8,
                TelepicDatabase.MIGRATION_8_9,
                TelepicDatabase.MIGRATION_9_10,
                TelepicDatabase.MIGRATION_10_11,
            )
            .build()
    }

    private val cloudDestinationDao: CloudDestinationDao by lazy { database.cloudDestinationDao() }
    private val cloudManifestDao: CloudMediaManifestDao by lazy { database.cloudMediaManifestDao() }
    private val backupQueueDao: com.telepic.data.backup.db.BackupQueueDao by lazy { database.backupQueueDao() }
    private val mediaOrganizationDao: com.telepic.data.organization.db.MediaOrganizationDao by lazy { database.mediaOrganizationDao() }
    private val customAlbumDao: com.telepic.data.media.db.CustomAlbumDao by lazy { database.customAlbumDao() }

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
        MediaStoreMediaLoader(
            context = appContext,
            hiddenIdsProvider = { mediaOrganizationRepository.hiddenIds() },
            typeFilterProvider = timelineFilterProvider,
        )
    }

    /**
     * Discovery for the backup engine — trashed media excluded, archived media **included**.
     *
     * Deliberately not [mediaLoader]: the timeline hides archived *and* trashed media, but archiving
     * is a timeline-organising decision rather than "do not keep this", so reusing that filter
     * silently dropped archived photos out of the user's backup set with nothing said. Trashed media
     * is still excluded — it is on its way out. (An item already queued uploads either way, because
     * the worker reads the queue rather than MediaStore, so the two paths no longer disagree.)
     */
    private val backupDiscoveryLoader: MediaStoreMediaLoader by lazy {
        MediaStoreMediaLoader(
            context = appContext,
            hiddenIdsProvider = { mediaOrganizationRepository.trashedIds.first() },
        )
    }

    override val localMediaRepository: LocalMediaRepository by lazy {
        LocalMediaRepositoryImpl(
            loader = mediaLoader,
            // Search is the timeline plus one WHERE clause, so it gets the same wiring as [mediaLoader]
            // — the same display scope, the same kind filter. Built per query because the term lives
            // in the loader.
            searchLoaderFactory = { query ->
                MediaStoreMediaLoader(
                    context = appContext,
                    hiddenIdsProvider = { mediaOrganizationRepository.hiddenIds() },
                    typeFilterProvider = timelineFilterProvider,
                    searchQueryProvider = { query },
                )
            },
        )
    }

    override val localMediaLookup: LocalMediaLookup by lazy {
        MediaStoreLocalLookup(
            context = appContext,
            hiddenIdsProvider = { mediaOrganizationRepository.hiddenIds() },
            typeFilterProvider = timelineFilterProvider,
        )
    }

    override val mediaOrganizationRepository: MediaOrganizationRepository by lazy {
        DefaultMediaOrganizationRepository(database, mediaOrganizationDao)
    }

    override val mediaDeleter: LocalMediaDeleter by lazy { MediaStoreLocalDeleter(appContext) }

    private val mediaLocationDao: com.telepic.data.media.db.MediaLocationDao by lazy { database.mediaLocationDao() }

    override val mapLocationRepository: MapLocationRepository by lazy {
        DefaultMapLocationRepository(
            dao = mediaLocationDao,
            pageLoader = mediaLoader,
            extractor = AndroidLocationExtractor(appContext),
            // The map sweep has its own page cap, and without a cursor every open restarts at offset 0
            // — so a library past the cap can never be covered no matter how often the screen opens.
            cursorStore = PreferencesScanCursorStore(appContext.telepicDataStore),
        )
    }

    override val mapCameraRepository: MapCameraRepository by lazy {
        MapCameraRepositoryImpl(appContext.telepicDataStore)
    }

    override val mediaMetadataReader: MediaMetadataReader by lazy {
        AndroidMediaMetadataReader(appContext)
    }

    override val restoreRepository: RestoreRepository by lazy {
        DefaultRestoreRepository(
            cloudRepository = cloudRepository,
            publisher = AndroidMediaStorePublisher(appContext),
            // App-owned record of (remote identity -> published uri) so a repeat "Save to device"
            // reuses the earlier copy instead of publishing a duplicate. Keyed on the real Telegram
            // identity, never on name/size/MIME.
            ledger = DataStoreRestoreLedger(appContext.telepicDataStore),
        )
    }

    override val albumRepository: AlbumRepository by lazy {
        MediaStoreAlbumRepository(
            context = appContext,
            hiddenIdsProvider = { mediaOrganizationRepository.hiddenIds() },
            typeFilterProvider = timelineFilterProvider,
        )
    }

    /**
     * The SELECT_FOLDER picker's folder list. Trashed-only exclusion and no type filter, so it agrees
     * with the per-bucket [MediaStoreBucketLoader]s the coordinator scans — see [backupAlbumRepository].
     */
    override val backupAlbumRepository: AlbumRepository by lazy {
        MediaStoreAlbumRepository(
            context = appContext,
            hiddenIdsProvider = { mediaOrganizationRepository.trashedIds.first() },
        )
    }

    override val customAlbumRepository: CustomAlbumRepository by lazy {
        DefaultCustomAlbumRepository(
            db = database,
            dao = customAlbumDao,
            lookup = localMediaLookup,
            hiddenIdsProvider = { mediaOrganizationRepository.hiddenIds() },
        )
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
            db = database,
            queueDao = backupQueueDao,
            manifestDao = cloudManifestDao,
            hasher = contentHasher,
        )
    }

    override val backupRepository: BackupRepository by lazy {
        DefaultBackupRepository(
            db = database,
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
        WorkManagerBackupScheduler(appContext, settingsRepository)
    }

    override val backupCoordinator: BackupCoordinator by lazy {
        DefaultBackupCoordinator(
            repository = backupRepository,
            recognition = backupRecognitionRepository,
            scheduler = backupWorkScheduler,
            onboardingRepository = onboardingRepository,
            // null bucket = whole library; a bucket id = the same projection scoped to that folder,
            // so SELECT_FOLDER discovery reads exactly what the user chose. Both use the backup
            // discovery filter (trashed only), not the timeline's (archived + trashed).
            pageLoaderFactory = { bucketId ->
                if (bucketId == null) {
                    backupDiscoveryLoader
                } else {
                    MediaStoreBucketLoader(
                        context = appContext,
                        bucketId = bucketId,
                        hiddenIdsProvider = { mediaOrganizationRepository.trashedIds.first() },
                    )
                }
            },
            // Persists how far each loader's discovery pass reached, so a library larger than the
            // per-pass cap converges over successive ticks instead of re-scanning its newest slice.
            cursorStore = PreferencesScanCursorStore(appContext.telepicDataStore),
        )
    }
}
