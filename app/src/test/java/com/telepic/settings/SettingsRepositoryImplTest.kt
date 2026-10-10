package com.telepic.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies real theme-preference persistence through DataStore, including the safe default
 * when nothing has been written yet.
 */
class SettingsRepositoryImplTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newRepository(name: String): SettingsRepositoryImpl {
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "$name.preferences_pb") },
        )
        return SettingsRepositoryImpl(dataStore)
    }

    @Test
    fun `defaults to dark when nothing is persisted`() = runTest {
        val repository = newRepository("empty")
        assertEquals(ThemeMode.DARK, repository.themeMode.first())
    }

    @Test
    fun `persists and reads back the selected theme mode`() = runTest {
        val repository = newRepository("saved")
        repository.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, repository.themeMode.first())
        repository.setThemeMode(ThemeMode.SYSTEM)
        assertEquals(ThemeMode.SYSTEM, repository.themeMode.first())
    }
}
