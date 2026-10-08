package com.telepix.onboarding

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies onboarding persistence through real DataStore: the safe defaults on a fresh
 * install and round-tripping completion + backup preference.
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
    }

    @Test
    fun `completion persists and a returning user skips onboarding`() = runTest {
        val repository = newRepository("completed")
        repository.setCompleted(true)
        // A new repository over the same store simulates a process/app restart.
        val reopened = OnboardingRepositoryImpl(
            PreferenceDataStoreFactory.create { File(tempFolder.root, "completed.preferences_pb") },
        )
        assertEquals(true, reopened.isCompleted.first())
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
}
