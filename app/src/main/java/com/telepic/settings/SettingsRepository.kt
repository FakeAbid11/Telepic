package com.telepic.settings

import kotlinx.coroutines.flow.Flow

/**
 * Contract for reading/writing user settings.
 *
 * Every preference here is *load-bearing*: it is read by the component that actually enforces it
 * (WorkManager constraints for the transport options, the MediaStore query for the timeline filters,
 * the video player for the mute default, the root gate for the app lock). The interface deliberately
 * holds no value that no code reads — a setting the app cannot honour would be a claim it cannot keep.
 */
interface SettingsRepository {
    /** The persisted theme preference, defaulting to [ThemeMode.Default]. */
    val themeMode: Flow<ThemeMode>

    suspend fun setThemeMode(mode: ThemeMode)

    /** Which networks the backup engine may upload over. Applied as a WorkManager constraint. */
    val backupNetworkPreference: Flow<BackupNetworkPreference>

    suspend fun setBackupNetworkPreference(preference: BackupNetworkPreference)

    /** When true, uploads only run while the device is charging. */
    val backupRequiresCharging: Flow<Boolean>

    suspend fun setBackupRequiresCharging(required: Boolean)

    /** When true, uploads only run while the battery is not low. */
    val backupRequiresBatteryNotLow: Flow<Boolean>

    suspend fun setBackupRequiresBatteryNotLow(required: Boolean)

    /**
     * Whether videos appear in the timeline and albums.
     *
     * Display-only by design: it filters the MediaStore queries the *library views* use and never
     * changes backup eligibility, which is governed solely by the backup preference.
     */
    val showVideosInTimeline: Flow<Boolean>

    suspend fun setShowVideosInTimeline(show: Boolean)

    /** Whether animated GIFs appear in the timeline and albums. Display-only, like [showVideosInTimeline]. */
    val showGifsInTimeline: Flow<Boolean>

    suspend fun setShowGifsInTimeline(show: Boolean)

    /**
     * Whether a video starts with sound. Defaults to muted: autoplaying with sound hijacks whatever
     * the user was already listening to, and media3 offers no middle ground while it holds audio focus.
     */
    val startVideosMuted: Flow<Boolean>

    suspend fun setStartVideosMuted(muted: Boolean)

    /**
     * Whether the app requires device-credential authentication before showing its content.
     *
     * Enforced by the root gate, and only when the device actually has a credential configured — with
     * no PIN/pattern/password there is nothing to authenticate against, so the setting is reported as
     * unavailable rather than silently doing nothing.
     */
    val appLockEnabled: Flow<Boolean>

    suspend fun setAppLockEnabled(enabled: Boolean)
}
