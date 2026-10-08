package com.telepix.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.telepix.settings.SettingsRepository
import com.telepix.settings.SettingsRepositoryImpl

/** Single DataStore instance for the whole process. */
private val Context.telepixDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "telepix_settings",
)

/**
 * Lightweight manual dependency container.
 *
 * Phase 1 deliberately avoids a DI framework to keep the build simple; the container is
 * shaped so a future Hilt/Koin migration swaps the implementation without touching callers.
 */
interface AppContainer {
    val settingsRepository: SettingsRepository
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(context.telepixDataStore)
    }
}
