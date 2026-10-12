package com.telepic.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * [SettingsRepository] backed by Jetpack DataStore (Preferences).
 *
 * Reads are failure-tolerant: any read error emits the default rather than crashing the UI. Every
 * default is the *safe* one for the component that consumes it — videos and GIFs are shown, uploads
 * are unconstrained, videos start muted, and the app lock is off until the user turns it on.
 */
class SettingsRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val themeMode: Flow<ThemeMode> = dataStore.data
        .map { preferences -> ThemeMode.fromKey(preferences[KEY_THEME_MODE]) }
        .catch { emit(ThemeMode.Default) }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[KEY_THEME_MODE] = mode.name
        }
    }

    override val backupNetworkPreference: Flow<BackupNetworkPreference> = dataStore.data
        .map { preferences -> BackupNetworkPreference.fromKey(preferences[KEY_BACKUP_NETWORK]) }
        .catch { emit(BackupNetworkPreference.Default) }

    override suspend fun setBackupNetworkPreference(preference: BackupNetworkPreference) {
        dataStore.edit { preferences ->
            preferences[KEY_BACKUP_NETWORK] = preference.name
        }
    }

    override val backupRequiresCharging: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[KEY_BACKUP_REQUIRES_CHARGING] ?: false }
        .catch { emit(false) }

    override suspend fun setBackupRequiresCharging(required: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_BACKUP_REQUIRES_CHARGING] = required
        }
    }

    override val backupRequiresBatteryNotLow: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[KEY_BACKUP_REQUIRES_BATTERY_NOT_LOW] ?: false }
        .catch { emit(false) }

    override suspend fun setBackupRequiresBatteryNotLow(required: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_BACKUP_REQUIRES_BATTERY_NOT_LOW] = required
        }
    }

    override val showVideosInTimeline: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[KEY_SHOW_VIDEOS] ?: true }
        .catch { emit(true) }

    override suspend fun setShowVideosInTimeline(show: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_SHOW_VIDEOS] = show
        }
    }

    override val showGifsInTimeline: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[KEY_SHOW_GIFS] ?: true }
        .catch { emit(true) }

    override suspend fun setShowGifsInTimeline(show: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_SHOW_GIFS] = show
        }
    }

    override val startVideosMuted: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[KEY_START_VIDEOS_MUTED] ?: true }
        .catch { emit(true) }

    override suspend fun setStartVideosMuted(muted: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_START_VIDEOS_MUTED] = muted
        }
    }

    override val appLockEnabled: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[KEY_APP_LOCK] ?: false }
        .catch { emit(false) }

    override suspend fun setAppLockEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_APP_LOCK] = enabled
        }
    }

    companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_BACKUP_NETWORK = stringPreferencesKey("backup_network")
        val KEY_BACKUP_REQUIRES_CHARGING = booleanPreferencesKey("backup_requires_charging")
        val KEY_BACKUP_REQUIRES_BATTERY_NOT_LOW = booleanPreferencesKey("backup_requires_battery_not_low")
        val KEY_SHOW_VIDEOS = booleanPreferencesKey("timeline_show_videos")
        val KEY_SHOW_GIFS = booleanPreferencesKey("timeline_show_gifs")
        val KEY_START_VIDEOS_MUTED = booleanPreferencesKey("viewer_start_videos_muted")
        val KEY_APP_LOCK = booleanPreferencesKey("app_lock_enabled")
    }
}
