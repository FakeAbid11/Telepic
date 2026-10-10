package com.telepic.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepic.data.media.AlbumRepository
import com.telepic.domain.media.Album
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Immutable UI state for the onboarding flow and startup routing. */
data class OnboardingUiState(
    /** True until the persisted values have loaded, so the router can avoid a wrong flash. */
    val isLoading: Boolean = true,
    val isCompleted: Boolean = false,
    val backupPreference: BackupPreference? = null,
    /** Buckets chosen for SELECT_FOLDER; empty until the user picks folders. */
    val backupBucketIds: Set<Long> = emptySet(),
)

/**
 * Drives onboarding decisions and persistence. Held at the activity level so state survives
 * configuration changes and process recreation (backed by DataStore, not local UI state).
 *
 * [onBackupChoiceChanged] is invoked whenever a backup choice is saved or onboarding completes, so a
 * first-run BACKUP_ALL / SELECT_FOLDER takes effect immediately (enqueue + schedule attempt, gated on
 * permission/auth/connectivity by the coordinator) instead of waiting for the next app launch. It is
 * injected to keep this ViewModel free of the backup engine, and defaults to a no-op.
 */
class OnboardingViewModel(
    private val repository: OnboardingRepository,
    private val albumRepository: AlbumRepository? = null,
    private val onBackupChoiceChanged: suspend () -> Unit = {},
) : ViewModel() {

    private val loaded = MutableStateFlow(false)

    val uiState: StateFlow<OnboardingUiState> = combine(
        repository.isCompleted,
        repository.backupPreference,
        repository.backupBucketIds,
        loaded,
    ) { completed, backup, buckets, hasLoaded ->
        OnboardingUiState(
            isLoading = !hasLoaded,
            isCompleted = completed,
            backupPreference = backup,
            backupBucketIds = buckets,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = OnboardingUiState(),
    )

    /** Device folders offered by the SELECT_FOLDER picker, loaded on demand. */
    private val _folders = MutableStateFlow<List<Album>>(emptyList())
    val folders: StateFlow<List<Album>> = _folders.asStateFlow()

    init {
        // The first emission from DataStore means persisted state is available.
        viewModelScope.launch {
            repository.isCompleted.collect { loaded.value = true }
        }
    }

    /** (Re)load the bucket list when the folder picker opens. */
    fun loadFolders() {
        viewModelScope.launch {
            _folders.value = albumRepository?.albums().orEmpty()
        }
    }

    fun setBackupPreference(preference: BackupPreference) {
        viewModelScope.launch {
            repository.setBackupPreference(preference)
            // A changed choice re-syncs immediately (enqueue + schedule attempt), not only at startup.
            onBackupChoiceChanged()
        }
    }

    /** Persist a folder-scoped choice: the preference plus exactly the buckets the user confirmed. */
    fun setBackupChoice(preference: BackupPreference, bucketIds: Set<Long>? = null) {
        viewModelScope.launch {
            repository.setBackupPreference(preference)
            if (bucketIds != null) repository.setBackupBucketIds(bucketIds)
            onBackupChoiceChanged()
        }
    }

    /** Persists completion and applies the chosen backup policy now; the router then leaves onboarding. */
    fun completeOnboarding() {
        viewModelScope.launch {
            repository.setCompleted(true)
            onBackupChoiceChanged()
        }
    }
}
