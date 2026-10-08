package com.telepix.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
)

/**
 * Drives onboarding decisions and persistence. Held at the activity level so state survives
 * configuration changes and process recreation (backed by DataStore, not local UI state).
 */
class OnboardingViewModel(
    private val repository: OnboardingRepository,
) : ViewModel() {

    private val loaded = MutableStateFlow(false)

    val uiState: StateFlow<OnboardingUiState> =
        combine(repository.isCompleted, repository.backupPreference, loaded) { completed, backup, hasLoaded ->
            OnboardingUiState(
                isLoading = !hasLoaded,
                isCompleted = completed,
                backupPreference = backup,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = OnboardingUiState(),
        )

    init {
        // The first emission from DataStore means persisted state is available.
        viewModelScope.launch {
            repository.isCompleted.collect { loaded.value = true }
        }
    }

    fun setBackupPreference(preference: BackupPreference) {
        viewModelScope.launch {
            repository.setBackupPreference(preference)
        }
    }

    /** Persists completion; the router then switches the startup destination to the main app. */
    fun completeOnboarding() {
        viewModelScope.launch {
            repository.setCompleted(true)
        }
    }
}
