package com.telepic.onboarding

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies onboarding persistence through real DataStore: the safe defaults on a fresh
 * install and round-tripping completion + backup preference.
 *
 * The stored shape of the folder selection is a **string set of decimal bucket ids**, and that must
 * not change: the backup engine reads the same flow to decide which folders to scan, so a format
 * change would silently forget the user's choice on upgrade. The last two tests pin that contract.
 */
class OnboardingRepositoryImplTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newRepository(name: String): OnboardingRepositoryImpl {
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "$name.preferences_pb") },
        )
        return OnboardingRepositoryImpl(dataStore)
    }

    @Test
    fun `fresh install is not completed and has no backup preference`() = runTest {
        val repository = newRepository("fresh")
        assertFalse(repository.isCompleted.first())
        assertNull(repository.backupPreference.first())
        assertEquals(emptySet<Long>(), repository.backupBucketIds.first())
    }

    @Test
    fun `selected folder buckets persist and clear honestly`() = runTest {
        val repository = newRepository("buckets")
        repository.setBackupBucketIds(setOf(11L, 22L))
        assertEquals(setOf(11L, 22L), repository.backupBucketIds.first())
        // An empty pick removes the key rather than storing an empty set — SELECT_FOLDER with no
        // folders must read back as "nothing picked", the NOT_NOW equivalent.
        repository.setBackupBucketIds(emptySet())
        assertEquals(emptySet<Long>(), repository.backupBucketIds.first())
    }

    @Test
    fun `completion persists and a returning user skips onboarding`() = runTest {
        // One DataStore instance for the file; a second repository over it simulates a
        // returning user / app restart reading the persisted completion.
        val dataStore = PreferenceDataStoreFactory.create {
            File(tempFolder.root, "completed.preferences_pb")
        }
        OnboardingRepositoryImpl(dataStore).setCompleted(true)
        val reopened = OnboardingRepositoryImpl(dataStore)
        assertTrue(reopened.isCompleted.first())
    }

    @Test
    fun `each backup preference persists`() = runTest {
        val repository = newRepository("backup")
        BackupPreference.entries.forEach { preference ->
            repository.setBackupPreference(preference)
            assertEquals(preference, repository.backupPreference.first())
        }
    }

    @Test
    fun `clearing the backup preference stores null`() = runTest {
        val repository = newRepository("clear")
        repository.setBackupPreference(BackupPreference.BACKUP_ALL)
        repository.setBackupPreference(null)
        assertNull(repository.backupPreference.first())
    }

    @Test
    fun `folder ids persisted by an earlier build still read back`() = runTest {
        // Written through the raw key, exactly as a previous version of the app left it — the point
        // is that upgrading must not lose which folders the user chose.
        val dataStore = PreferenceDataStoreFactory.create {
            File(tempFolder.root, "legacy_buckets.preferences_pb")
        }
        dataStore.edit { preferences ->
            preferences[OnboardingRepositoryImpl.KEY_BACKUP_BUCKET_IDS] = setOf("11", "22", "33")
        }

        val repository = OnboardingRepositoryImpl(dataStore)

        assertEquals(setOf(11L, 22L, 33L), repository.backupBucketIds.first())
    }

    @Test
    fun `an unparseable stored folder id is skipped, not fatal`() = runTest {
        // A corrupt or hand-edited store must not take down the picker — nor the backup engine,
        // which reads this same flow to decide what to scan. Bad entries drop out; good ones survive.
        val dataStore = PreferenceDataStoreFactory.create {
            File(tempFolder.root, "corrupt_buckets.preferences_pb")
        }
        dataStore.edit { preferences ->
            preferences[OnboardingRepositoryImpl.KEY_BACKUP_BUCKET_IDS] = setOf("11", "not-a-number")
        }

        val repository = OnboardingRepositoryImpl(dataStore)

        assertEquals(setOf(11L), repository.backupBucketIds.first())
    }
}
