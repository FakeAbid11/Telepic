package com.telepic.onboarding

import android.net.Uri
import androidx.paging.PagingData
import com.telepic.data.media.AlbumRepository
import com.telepic.domain.media.Album
import com.telepic.domain.media.PhotosItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies that saving a backup choice or completing onboarding not only persists through the
 * repository but also re-triggers the backup engine immediately (the injected [onBackupChoiceChanged]
 * callback). This is the §9 fix: without it, a first-run BACKUP_ALL sat dormant until the next launch.
 *
 * Runs on Robolectric so `viewModelScope`'s Main dispatcher is the synchronizable test looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OnboardingViewModelTest {

    private class FakeRepository(private val failWrites: Boolean = false) : OnboardingRepository {
        val completed = MutableStateFlow(false)
        val backup = MutableStateFlow<BackupPreference?>(null)
        val buckets = MutableStateFlow<Set<Long>>(emptySet())
        override val isCompleted: Flow<Boolean> get() = completed
        override val backupPreference: Flow<BackupPreference?> get() = backup
        override val backupBucketIds: Flow<Set<Long>> get() = buckets
        var setCompletedCalls = 0
        var setBackupCalls = 0
        var setBucketCalls = 0
        override suspend fun setCompleted(completed: Boolean) {
            setCompletedCalls++
            if (failWrites) throw IllegalStateException("DataStore write failed")
            this.completed.value = completed
        }
        override suspend fun setBackupPreference(preference: BackupPreference?) {
            setBackupCalls++
            if (failWrites) throw IllegalStateException("DataStore write failed")
            backup.value = preference
        }
        override suspend fun setBackupBucketIds(ids: Set<Long>) {
            setBucketCalls++
            if (failWrites) throw IllegalStateException("DataStore write failed")
            buckets.value = ids
        }
    }

    /** A bucket query that fails the way a revoked or limited media grant makes it fail. */
    private class ExplodingAlbumRepository : AlbumRepository {
        override suspend fun albums(): List<Album> = throw SecurityException("media access was revoked")
        override fun mediaInBucket(bucketId: Long): Flow<PagingData<PhotosItem>> = flowOf(PagingData.empty())
        override fun refresh() = Unit
    }

    /** Records that the folder query was made, so "loaded on demand" is actually observed. */
    private class FakeFolders(private val folders: List<Album>) : AlbumRepository {
        var calls = 0
        override suspend fun albums(): List<Album> {
            calls++
            return folders
        }

        override fun mediaInBucket(bucketId: Long): Flow<PagingData<PhotosItem>> = flowOf(PagingData.empty())
        override fun refresh() = Unit
    }

    private fun settle() {
        repeat(5) { shadowOf(android.os.Looper.getMainLooper()).idle() }
    }

    @Test
    fun `choosing a backup preference persists and syncs the backup engine`() {
        val repository = FakeRepository()
        val syncs = AtomicInteger()
        val vm = OnboardingViewModel(repository, onBackupChoiceChanged = { syncs.incrementAndGet() })

        vm.setBackupPreference(BackupPreference.BACKUP_ALL)
        settle()

        assertEquals(1, repository.setBackupCalls)
        assertEquals(BackupPreference.BACKUP_ALL, repository.backup.value)
        // The choice takes effect now, not only at next launch.
        assertEquals(1, syncs.get())
    }

    @Test
    fun `completing onboarding persists and syncs the backup engine`() {
        val repository = FakeRepository()
        val syncs = AtomicInteger()
        val vm = OnboardingViewModel(repository, onBackupChoiceChanged = { syncs.incrementAndGet() })

        vm.completeOnboarding()
        settle()

        assertEquals(1, repository.setCompletedCalls)
        assertTrue(repository.completed.value)
        assertEquals(1, syncs.get())
    }

    @Test
    fun `default callback is a no-op and persistence still happens`() {
        val repository = FakeRepository()
        val vm = OnboardingViewModel(repository)

        vm.setBackupPreference(BackupPreference.NOT_NOW)
        settle()

        assertEquals(BackupPreference.NOT_NOW, repository.backup.value)
        // uiState reflects the loaded persisted preference with no crash from the missing callback.
        val state = vm.uiState.value
        assertTrue("expected not loading", !state.isLoading)
        assertEquals(BackupPreference.NOT_NOW, state.backupPreference)
    }

    @Test
    fun `SELECT_FOLDER choice persists preference and picked buckets together and syncs`() {
        val repository = FakeRepository()
        val syncs = AtomicInteger()
        val vm = OnboardingViewModel(repository, onBackupChoiceChanged = { syncs.incrementAndGet() })

        vm.setBackupChoice(BackupPreference.SELECT_FOLDER, bucketIds = setOf(11L, 22L))
        settle()

        assertEquals(1, repository.setBackupCalls)
        assertEquals(1, repository.setBucketCalls)
        assertEquals(BackupPreference.SELECT_FOLDER, repository.backup.value)
        assertEquals(setOf(11L, 22L), repository.buckets.value)
        // The router/state surface exposes the picked buckets so the step can show the real count.
        assertEquals(setOf(11L, 22L), vm.uiState.value.backupBucketIds)
        assertEquals(1, syncs.get())
    }

    @Test
    fun `a failing backup sync is reported instead of closing the app`() {
        // The reported crash. The sync runs inside `viewModelScope`, so an escaping exception was an
        // uncaught exception on the main thread: the app closed itself the moment the user confirmed
        // a folder. The choice is already persisted by then, so the flow must simply carry on.
        val repository = FakeRepository()
        val vm = OnboardingViewModel(
            repository,
            onBackupChoiceChanged = { throw SecurityException("media access was revoked") },
        )

        vm.setBackupChoice(BackupPreference.SELECT_FOLDER, bucketIds = setOf(11L))
        settle()

        assertEquals(BackupPreference.SELECT_FOLDER, repository.backup.value)
        assertEquals(setOf(11L), repository.buckets.value)
        assertTrue("expected the failure to be surfaced, not swallowed", vm.setupFailed.value)
    }

    @Test
    fun `a failed persist is reported and the backup engine is never asked to start`() {
        val repository = FakeRepository(failWrites = true)
        val syncs = AtomicInteger()
        val vm = OnboardingViewModel(repository, onBackupChoiceChanged = { syncs.incrementAndGet() })

        vm.setBackupChoice(BackupPreference.SELECT_FOLDER, bucketIds = setOf(11L))
        settle()

        assertEquals(0, syncs.get())
        assertTrue(vm.setupFailed.value)
        // Nothing was written, so Continue stays disabled — and this is what explains why.
        assertEquals(null, repository.backup.value)
    }

    @Test
    fun `a failed folder query degrades to an empty picker instead of crashing`() {
        val repository = FakeRepository()
        val vm = OnboardingViewModel(repository, foldersRepository = ExplodingAlbumRepository())

        vm.loadFolders()
        settle()

        // The picker's own honest empty copy ("allow photo access first, then reopen") is the right
        // outcome; the failure must not escape `viewModelScope`.
        assertTrue("expected the picker to stop loading", vm.foldersLoaded.value)
        assertEquals(emptyList<Album>(), vm.folders.value)
        // A permission/provider failure is not a *choice* failure, so it does not claim one.
        assertTrue("a failed query must not be reported as a failed choice", !vm.setupFailed.value)
    }

    @Test
    fun `the folder picker reads the backup-scoped repository, and only when asked`() {
        // The picker's list is a *backup* choice, so it must come from the backup-scoped repository
        // rather than the display-filtered one — otherwise a folder whose media the timeline hides
        // cannot be selected at all. Loading is on demand: opening the step must not scan MediaStore.
        val folders = listOf(
            Album(
                bucketId = 42L,
                title = "Camera",
                coverUri = Uri.parse("content://media/external/images/media/42"),
                coverIsVideo = false,
                count = 7,
                latestMillis = 1_700_000_000_000L,
                relativePath = "DCIM/Camera/",
            ),
        )
        val folderSource = FakeFolders(folders)
        val vm = OnboardingViewModel(FakeRepository(), foldersRepository = folderSource)

        assertEquals(0, folderSource.calls)

        vm.loadFolders()
        settle()

        assertEquals(1, folderSource.calls)
        assertTrue("expected the picker to stop loading", vm.foldersLoaded.value)
        assertEquals(folders, vm.folders.value)
        // The path survives the round trip, which is what lets the dialog disambiguate later.
        assertEquals("DCIM/Camera/", vm.folders.value.single().relativePath)
    }
}
