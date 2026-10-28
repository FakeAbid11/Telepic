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

    @Test
    fun `transport preferences default to the unconstrained, non-destructive choice`() = runTest {
        val repository = newRepository("transport_defaults")
        assertEquals(BackupNetworkPreference.ANY_NETWORK, repository.backupNetworkPreference.first())
        assertEquals(false, repository.backupRequiresCharging.first())
        assertEquals(false, repository.backupRequiresBatteryNotLow.first())
    }

    @Test
    fun `transport preferences persist`() = runTest {
        val repository = newRepository("transport_saved")
        repository.setBackupNetworkPreference(BackupNetworkPreference.WIFI_ONLY)
        repository.setBackupRequiresCharging(true)
        repository.setBackupRequiresBatteryNotLow(true)

        assertEquals(BackupNetworkPreference.WIFI_ONLY, repository.backupNetworkPreference.first())
        assertEquals(true, repository.backupRequiresCharging.first())
        assertEquals(true, repository.backupRequiresBatteryNotLow.first())
    }

    @Test
    fun `media preferences default to showing everything and starting muted`() = runTest {
        val repository = newRepository("media_defaults")
        // The safe defaults: nothing is hidden from the library, and playback does not take audio.
        assertEquals(true, repository.showVideosInTimeline.first())
        assertEquals(true, repository.showGifsInTimeline.first())
        assertEquals(true, repository.startVideosMuted.first())
    }

    @Test
    fun `media preferences persist`() = runTest {
        val repository = newRepository("media_saved")
        repository.setShowVideosInTimeline(false)
        repository.setShowGifsInTimeline(false)
        repository.setStartVideosMuted(false)

        assertEquals(false, repository.showVideosInTimeline.first())
        assertEquals(false, repository.showGifsInTimeline.first())
        assertEquals(false, repository.startVideosMuted.first())
    }

    @Test
    fun `the app lock defaults to off and persists once enabled`() = runTest {
        val repository = newRepository("app_lock")
        assertEquals(false, repository.appLockEnabled.first())

        repository.setAppLockEnabled(true)
        assertEquals(true, repository.appLockEnabled.first())
    }

    @Test
    fun `fingerprint unlock defaults to on and persists once turned off`() = runTest {
        val repository = newRepository("app_lock_biometric")
        // On by default: it only ever adds the biometric on top of a credential that is still
        // required, so the default cannot weaken the gate.
        assertEquals(true, repository.appLockBiometricEnabled.first())

        repository.setAppLockBiometricEnabled(false)
        assertEquals(false, repository.appLockBiometricEnabled.first())
    }

    @Test
    fun `an unknown persisted network value falls back to the default`() = runTest {
        // A value written by a future build (or a corrupted file) must not throw on read.
        assertEquals(BackupNetworkPreference.Default, BackupNetworkPreference.fromKey("SOMETHING_ELSE"))
        assertEquals(BackupNetworkPreference.Default, BackupNetworkPreference.fromKey(null))
        assertEquals(BackupNetworkPreference.WIFI_ONLY, BackupNetworkPreference.fromKey("wifi_only"))
    }
}
