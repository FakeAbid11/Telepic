package com.telepix.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.telepix.data.media.AndroidMediaChangeWatcher
import com.telepix.data.media.LocalMediaRepository
import com.telepix.data.media.LocalMediaRepositoryImpl
import com.telepix.data.media.MediaChangeWatcher
import com.telepix.data.media.MediaStoreMediaLoader
import com.telepix.onboarding.OnboardingRepository
import com.telepix.onboarding.OnboardingRepositoryImpl
import com.telepix.settings.SettingsRepository
import com.telepix.settings.SettingsRepositoryImpl
import com.telepix.telegram.TelegramAuthController
import com.telepix.telegram.UnavailableTelegramAuthController

/** Single DataStore instance for the whole process, shared by all preference repositories. */
private val Context.telepixDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "telepix_settings",
)

/**
 * Lightweight manual dependency container.
 *
 * Deliberately avoids a DI framework to keep the build simple; shaped so a future Hilt/Koin
 * migration swaps implementations without touching callers. Phase 4 replaces the Telegram
 * controller here with a TDLib-backed implementation.
 */
interface AppContainer {
    val settingsRepository: SettingsRepository
    val onboardingRepository: OnboardingRepository
    val telegramAuthController: TelegramAuthController
    val localMediaRepository: LocalMediaRepository
    val mediaChangeWatcher: MediaChangeWatcher
}

class DefaultAppContainer(private val context: Context) : AppContainer {

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(context.telepixDataStore)
    }

    override val onboardingRepository: OnboardingRepository by lazy {
        OnboardingRepositoryImpl(context.telepixDataStore)
    }

    override val telegramAuthController: TelegramAuthController by lazy {
        UnavailableTelegramAuthController()
    }

    override val localMediaRepository: LocalMediaRepository by lazy {
        LocalMediaRepositoryImpl(MediaStoreMediaLoader(context))
    }

    override val mediaChangeWatcher: MediaChangeWatcher by lazy {
        AndroidMediaChangeWatcher(context)
    }
}
