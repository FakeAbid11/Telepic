package com.telepic.onboarding

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    private class FakeRepository : OnboardingRepository {
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
            this.completed.value = completed
        }
        override suspend fun setBackupPreference(preference: BackupPreference?) {
            setBackupCalls++
            backup.value = preference
        }
        override suspend fun setBackupBucketIds(ids: Set<Long>) {
            setBucketCalls++
            buckets.value = ids
        }
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
}
