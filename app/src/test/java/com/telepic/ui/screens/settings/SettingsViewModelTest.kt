package com.telepic.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.backup.BackupCoordinator
import com.telepic.data.backup.BackupRepository
import com.telepic.data.backup.BulkBackupSummary
import com.telepic.data.media.AlbumRepository
import com.telepic.domain.backup.BackupItem
import com.telepic.domain.backup.BackupQueueStats
import com.telepic.domain.media.Album
import com.telepic.domain.media.LocalMedia
import com.telepic.onboarding.BackupPreference
import com.telepic.onboarding.OnboardingRepository
import com.telepic.settings.BackupNetworkPreference
import com.telepic.settings.SettingsRepository
import com.telepic.settings.ThemeMode
import com.telepic.telegram.TelegramAuthController
import com.telepic.telegram.TelegramAuthState
import com.telepic.telegram.TelegramUser
import androidx.paging.PagingData
import com.telepic.domain.media.PhotosItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Settings must reflect and change *real* state: the backup preference write goes through the same
 * repository + coordinator path onboarding uses; sign-out delegates to the live auth controller;
 * and the cache clear only ever touches the transient staging directory — never the TDLib session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsViewModelTest {

    private class FakeAuthController : TelegramAuthController {
        private val _state = MutableStateFlow<TelegramAuthState>(TelegramAuthState.NotConnected)
        override val state: StateFlow<TelegramAuthState> = _state.asStateFlow()
        override val isBackendAvailable: Boolean = true
        override fun start() = Unit
        override fun submitPhoneNumber(phoneNumber: String) = Unit
        override fun submitCode(code: String) = Unit
        override fun submitPassword(password: String) = Unit
        var logoutCalls = 0
        override fun logout() { logoutCalls++ }
        fun authorize() {
            _state.value = TelegramAuthState.Authorized(TelegramUser(id = 1L, firstName = "Ada", lastName = null, username = null))
        }
    }

    private class FakeOnboarding : OnboardingRepository {
        val completed = MutableStateFlow(true)
        val preference = MutableStateFlow<BackupPreference?>(null)
        val buckets = MutableStateFlow<Set<Long>>(emptySet())
        override val isCompleted: Flow<Boolean> get() = completed
        override val backupPreference: Flow<BackupPreference?> get() = preference
        override val backupBucketIds: Flow<Set<Long>> get() = buckets
        override suspend fun setCompleted(completed: Boolean) { this.completed.value = completed }
        override suspend fun setBackupPreference(preference: BackupPreference?) { this.preference.value = preference }
        override suspend fun setBackupBucketIds(ids: Set<Long>) { this.buckets.value = ids }
    }

    private class FakeAlbums(private val albums: List<Album>) : AlbumRepository {
        override suspend fun albums() = albums
        override fun mediaInBucket(bucketId: Long): Flow<PagingData<PhotosItem>> = flowOf(PagingData.empty())
        override fun refresh() = Unit
    }

    /**
     * The settings store, in memory. Every preference is a real flow so the ViewModel's own
     * `stateIn` mappings are exercised rather than bypassed.
     */
    private class FakeSettings : SettingsRepository {
        val theme = MutableStateFlow(ThemeMode.DARK)
        val network = MutableStateFlow(BackupNetworkPreference.Default)
        val charging = MutableStateFlow(false)
        val batteryNotLow = MutableStateFlow(false)
        val videos = MutableStateFlow(true)
        val gifs = MutableStateFlow(true)
        val startMuted = MutableStateFlow(true)
        val appLock = MutableStateFlow(false)

        override val themeMode: Flow<ThemeMode> get() = theme
        override suspend fun setThemeMode(mode: ThemeMode) { theme.value = mode }
        override val backupNetworkPreference: Flow<BackupNetworkPreference> get() = network
        override suspend fun setBackupNetworkPreference(preference: BackupNetworkPreference) { network.value = preference }
        override val backupRequiresCharging: Flow<Boolean> get() = charging
        override suspend fun setBackupRequiresCharging(required: Boolean) { charging.value = required }
        override val backupRequiresBatteryNotLow: Flow<Boolean> get() = batteryNotLow
        override suspend fun setBackupRequiresBatteryNotLow(required: Boolean) { batteryNotLow.value = required }
        override val showVideosInTimeline: Flow<Boolean> get() = videos
        override suspend fun setShowVideosInTimeline(show: Boolean) { videos.value = show }
        override val showGifsInTimeline: Flow<Boolean> get() = gifs
        override suspend fun setShowGifsInTimeline(show: Boolean) { gifs.value = show }
        override val startVideosMuted: Flow<Boolean> get() = startMuted
        override suspend fun setStartVideosMuted(muted: Boolean) { startMuted.value = muted }
        override val appLockEnabled: Flow<Boolean> get() = appLock
        override suspend fun setAppLockEnabled(enabled: Boolean) { appLock.value = enabled }
    }

    /** Records album-source invalidations, which is how a media-filter change reaches the timeline. */
    private class RefreshableAlbums(private val albums: List<Album>) : AlbumRepository {
        var refreshCalls = 0
        override suspend fun albums() = albums
        override fun mediaInBucket(bucketId: Long): Flow<PagingData<PhotosItem>> = flowOf(PagingData.empty())
        override fun refresh() { refreshCalls++ }
    }

    private class RecordingCoordinator : BackupCoordinator {
        var syncCalls = 0
        override val repository: BackupRepository get() = throw NotImplementedError()
        override suspend fun backup(media: LocalMedia) = BulkBackupSummary.Empty
        override suspend fun backupAll(media: List<LocalMedia>) = BulkBackupSummary.Empty
        override suspend fun retry(itemId: Long) = Unit
        override suspend fun cancel(itemId: Long) = Unit
        override suspend fun syncFromPreference() { syncCalls++ }
        override suspend fun startPendingBackup() = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        auth: FakeAuthController = FakeAuthController(),
        onboarding: FakeOnboarding = FakeOnboarding(),
        settings: FakeSettings = FakeSettings(),
        coordinator: RecordingCoordinator = RecordingCoordinator(),
        albums: List<Album> = emptyList(),
        albumRepository: AlbumRepository = FakeAlbums(albums),
    ) = SettingsViewModel(
        telegramAuthController = auth,
        onboardingRepository = onboarding,
        settingsRepository = settings,
        albumRepository = albumRepository,
        backupCoordinator = coordinator,
        appContext = ApplicationProvider.getApplicationContext<Context>(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `sign-out delegates to the live auth controller`() = runTest {
        val auth = FakeAuthController()
        val vm = viewModel(auth = auth)
        vm.logout()
        assertEquals(1, auth.logoutCalls)
    }

    @Test
    fun `backup preference changes persist through the shared repository and sync the engine`() = runTest {
        val onboarding = FakeOnboarding()
        val coordinator = RecordingCoordinator()
        val vm = viewModel(onboarding = onboarding, coordinator = coordinator)

        vm.setBackupChoice(BackupPreference.BACKUP_ALL)
        runCurrent()
        assertEquals(BackupPreference.BACKUP_ALL, onboarding.preference.value)
        assertEquals(1, coordinator.syncCalls)

        vm.setBackupChoice(BackupPreference.SELECT_FOLDER, bucketIds = setOf(3L, 4L))
        runCurrent()
        assertEquals(BackupPreference.SELECT_FOLDER, onboarding.preference.value)
        assertEquals(setOf(3L, 4L), onboarding.buckets.value)
        assertEquals(2, coordinator.syncCalls)
    }

    @Test
    fun `manual backup now re-runs the coordinator discovery path`() = runTest {
        val coordinator = RecordingCoordinator()
        val vm = viewModel(coordinator = coordinator)

        vm.backupNow()
        runCurrent()

        assertEquals(1, coordinator.syncCalls)
    }

    @Test
    fun `folder list loads from the album repository on demand`() = runTest {
        val album = Album(
            bucketId = 8L,
            title = "Camera",
            coverUri = Uri.parse("content://media/cover"),
            coverIsVideo = false,
            count = 5,
            latestMillis = 1L,
        )
        val vm = viewModel(albums = listOf(album))
        assertTrue(vm.folders.value.isEmpty())
        vm.loadFolders()
        runCurrent()
        assertEquals(listOf(album), vm.folders.value)
    }

    @Test
    fun `clearing the backup cache empties only the staging directory`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val staging = File(context.cacheDir, "telepic_backup_staging").apply { mkdirs() }
        val otherCache = File(context.cacheDir, "coil_disk_cache").apply { mkdirs() }
        File(staging, "staged_1.tmp").writeBytes(ByteArray(64))
        File(otherCache, "keep.bin").writeBytes(ByteArray(32))

        val vm = viewModel()
        // init already measured (Unconfined): the staging copy is on the books before the clear.
        assertEquals(64L, vm.storageSummary.value.stagingBytes)

        vm.clearBackupCache()
        runCurrent()

        assertTrue(staging.exists()) // the directory itself survives; only contents are transient
        assertEquals(0, (staging.listFiles() ?: emptyArray()).size)
        assertEquals(0L, vm.storageSummary.value.stagingBytes)
        // Nothing outside staging is touched.
        assertTrue(File(otherCache, "keep.bin").exists())
    }

    @Test
    fun `transport preferences persist and are reflected by the screen's own flows`() = runTest {
        val settings = FakeSettings()
        val vm = viewModel(settings = settings)

        vm.setBackupNetworkPreference(BackupNetworkPreference.WIFI_ONLY)
        vm.setBackupRequiresCharging(true)
        vm.setBackupRequiresBatteryNotLow(true)
        runCurrent()

        assertEquals(BackupNetworkPreference.WIFI_ONLY, settings.network.value)
        assertTrue(settings.charging.value)
        assertTrue(settings.batteryNotLow.value)
        // The rows read the ViewModel's flows, so they must re-emit what the store now holds.
        assertEquals(BackupNetworkPreference.WIFI_ONLY, vm.backupNetworkPreference.value)
        assertTrue(vm.backupRequiresCharging.value)
    }

    @Test
    fun `hiding a media kind persists it and invalidates the album source`() = runTest {
        val settings = FakeSettings()
        val albums = RefreshableAlbums(emptyList())
        val vm = viewModel(settings = settings, albumRepository = albums)

        vm.setShowVideosInTimeline(false)
        runCurrent()
        assertFalse(settings.videos.value)
        assertEquals(1, albums.refreshCalls)

        vm.setShowGifsInTimeline(false)
        runCurrent()
        assertFalse(settings.gifs.value)
        // A `cachedIn` pager keeps serving pages built with the old filter until it is invalidated, so
        // every filter change has to reach refresh() — not just the first one.
        assertEquals(2, albums.refreshCalls)
    }

    @Test
    fun `the app lock preference persists and reports unsupported without a device credential`() = runTest {
        val settings = FakeSettings()
        val vm = viewModel(settings = settings)

        // Robolectric's default KeyguardManager reports no device credential, so the row must be
        // switched off rather than offering a lock the device could never enforce.
        assertFalse(vm.isDeviceSecure)

        vm.setAppLockEnabled(true)
        runCurrent()
        assertTrue(settings.appLock.value)
    }

    @Test
    fun `the local library count is the sum of the visible albums`() = runTest {
        val vm = viewModel(
            albums = listOf(album(bucketId = 1L, count = 12), album(bucketId = 2L, count = 30)),
        )

        assertEquals(42, vm.localLibraryCount.value)
        assertTrue(vm.localLibraryCountLoaded.value)
    }

    @Test
    fun `an unreadable library leaves the count unknown rather than zero`() = runTest {
        val failing = object : AlbumRepository {
            override suspend fun albums(): List<Album> = throw IllegalStateException("provider unavailable")
            override fun mediaInBucket(bucketId: Long): Flow<PagingData<PhotosItem>> = flowOf(PagingData.empty())
            override fun refresh() = Unit
        }
        val vm = viewModel(albumRepository = failing)

        // Null, never 0: "0 photos" would be a claim about the user's library that nothing verified.
        assertNull(vm.localLibraryCount.value)
        assertTrue(vm.localLibraryCountLoaded.value)
    }

    private fun album(bucketId: Long, count: Int) = Album(
        bucketId = bucketId,
        title = "Album $bucketId",
        coverUri = Uri.parse("content://media/$bucketId"),
        coverIsVideo = false,
        count = count,
        latestMillis = 1L,
    )
}
