package com.telepic.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * [OnboardingRepository] backed by the shared Jetpack DataStore.
 *
 * Reads are failure-tolerant: any read error falls back to a safe default (not completed,
 * no preference) so onboarding never crashes on a corrupt store.
 */
class OnboardingRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
) : OnboardingRepository {

    override val isCompleted: Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[KEY_COMPLETED] ?: false }
        .catch { emit(false) }

    override val backupPreference: Flow<BackupPreference?> = dataStore.data
        .map { preferences -> BackupPreference.fromKey(preferences[KEY_BACKUP_PREFERENCE]) }
        .catch { emit(null) }

    override val backupBucketIds: Flow<Set<Long>> = dataStore.data
        .map { preferences -> preferences[KEY_BACKUP_BUCKET_IDS]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet() }
        .catch { emit(emptySet()) }

    override suspend fun setCompleted(completed: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_COMPLETED] = completed
        }
    }

    override suspend fun setBackupPreference(preference: BackupPreference?) {
        dataStore.edit { preferences ->
            if (preference == null) {
                preferences.remove(KEY_BACKUP_PREFERENCE)
            } else {
                preferences[KEY_BACKUP_PREFERENCE] = preference.name
            }
        }
    }

    override suspend fun setBackupBucketIds(ids: Set<Long>) {
        dataStore.edit { preferences ->
            if (ids.isEmpty()) {
                preferences.remove(KEY_BACKUP_BUCKET_IDS)
            } else {
                preferences[KEY_BACKUP_BUCKET_IDS] = ids.map { it.toString() }.toSet()
            }
        }
    }

    companion object {
        val KEY_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val KEY_BACKUP_PREFERENCE = stringPreferencesKey("backup_preference")
        val KEY_BACKUP_BUCKET_IDS = stringSetPreferencesKey("backup_bucket_ids")
    }
}
