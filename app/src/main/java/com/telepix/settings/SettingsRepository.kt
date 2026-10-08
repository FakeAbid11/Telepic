package com.telepix.settings

import kotlinx.coroutines.flow.Flow

/**
 * Contract for reading/writing user settings.
 *
 * Phase 1 only persists the theme preference, but the interface is shaped so later phases
 * (backup preferences, account state, media options) extend it without churn.
 */
interface SettingsRepository {
    /** The persisted theme preference, defaulting to [ThemeMode.Default]. */
    val themeMode: Flow<ThemeMode>

    suspend fun setThemeMode(mode: ThemeMode)
}
