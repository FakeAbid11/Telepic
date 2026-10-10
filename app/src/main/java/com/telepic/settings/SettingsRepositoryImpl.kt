package com.telepic.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * [SettingsRepository] backed by Jetpack DataStore (Preferences).
 *
 * Reads are failure-tolerant: any read error emits the default rather than crashing the UI.
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

    companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
