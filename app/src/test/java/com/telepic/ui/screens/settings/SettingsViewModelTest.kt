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
        coordinator: RecordingCoordinator = RecordingCoordinator(),
        albums: List<Album> = emptyList(),
    ) = SettingsViewModel(
        telegramAuthController = auth,
        onboardingRepository = onboarding,
        albumRepository = FakeAlbums(albums),
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
}
