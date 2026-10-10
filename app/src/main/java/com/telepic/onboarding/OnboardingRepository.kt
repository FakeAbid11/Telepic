package com.telepic.onboarding

import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for onboarding.
 *
 * Keeps DataStore access out of the UI: composables talk to [OnboardingViewModel], which
 * talks to this repository. Only onboarding completion and the backup preference are
 * persisted here — Android's permission APIs remain the authoritative source for permissions.
 */
interface OnboardingRepository {
    /** Whether the user has completed onboarding. Emits `false` on a fresh install. */
    val isCompleted: Flow<Boolean>

    /** The persisted backup preference, or `null` if none has been chosen yet. */
    val backupPreference: Flow<BackupPreference?>

    suspend fun setCompleted(completed: Boolean)

    suspend fun setBackupPreference(preference: BackupPreference?)
}
