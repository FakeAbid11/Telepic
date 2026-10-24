package com.telepic.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepic.data.media.AlbumRepository
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.media.Album
import kotlinx.coroutines.CancellationException
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
    /**
     * The **backup-scoped** album repository behind the SELECT_FOLDER picker — the same one the
     * per-bucket discovery loaders read, so the folders listed and the counts beside them describe
     * what a backup would actually take rather than what the timeline happens to display. See
     * `AppContainer.backupAlbumRepository`. Nullable so a test — or a build with no media grant —
     * degrades to the picker's own honest empty state instead of crashing.
     */
    private val foldersRepository: AlbumRepository? = null,
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

    /** False until [loadFolders] finishes, so the picker shows loading — not a false "empty". */
    private val _foldersLoaded = MutableStateFlow(true)
    val foldersLoaded: StateFlow<Boolean> = _foldersLoaded.asStateFlow()

    /**
     * True when the bucket read failed, as opposed to the device genuinely having no folders.
     *
     * The picker showed the same "no folders" copy for both, which blamed photo access for a
     * provider error and offered the user nothing to do. A failure needs its own words and a retry.
     */
    private val _foldersFailed = MutableStateFlow(false)
    val foldersFailed: StateFlow<Boolean> = _foldersFailed.asStateFlow()

    /**
     * True when the last backup choice could not be persisted or applied, so the step can say so
     * instead of leaving a disabled Continue button with no explanation.
     */
    private val _setupFailed = MutableStateFlow(false)
    val setupFailed: StateFlow<Boolean> = _setupFailed.asStateFlow()

    /**
     * True when the **completion** write failed.
     *
     * Kept apart from [setupFailed] because the two failures need different words: a failed backup
     * choice is fixed by choosing again, a failed completion is fixed by pressing the button again.
     * Sharing one flag made the last step answer a failed completion with the *backup* copy, which
     * pointed the user at the wrong control.
     */
    private val _completionFailed = MutableStateFlow(false)
    val completionFailed: StateFlow<Boolean> = _completionFailed.asStateFlow()

    init {
        // The first emission from DataStore means persisted state is available.
        viewModelScope.launch {
            repository.isCompleted.collect { loaded.value = true }
        }
    }

    /** (Re)load the bucket list when the folder picker opens. */
    fun loadFolders() {
        _foldersLoaded.value = false
        _foldersFailed.value = false
        viewModelScope.launch {
            try {
                _folders.value = foldersRepository?.albums().orEmpty()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // A failed bucket query (no media grant, a *limited* grant, a provider error) must
                // degrade to a state the picker can explain and retry — rather than escape
                // `viewModelScope`, where an uncaught exception on the main thread closes the app.
                TelepicLog.warn("OnboardingViewModel", "folder list could not be loaded", throwable)
                _folders.value = emptyList()
                _foldersFailed.value = true
            } finally {
                _foldersLoaded.value = true
            }
        }
    }

    fun setBackupPreference(preference: BackupPreference) {
        viewModelScope.launch {
            persistChoice { repository.setBackupPreference(preference) }
        }
    }

    /** Persist a folder-scoped choice: the preference plus exactly the buckets the user confirmed. */
    fun setBackupChoice(preference: BackupPreference, bucketIds: Set<Long>? = null) {
        viewModelScope.launch {
            persistChoice {
                repository.setBackupPreference(preference)
                if (bucketIds != null) repository.setBackupBucketIds(bucketIds)
            }
        }
    }

    /** Persists completion and applies the chosen backup policy now; the router then leaves onboarding. */
    fun completeOnboarding() {
        viewModelScope.launch {
            // Its own boundary, and its own flag: the completion is a different write from the backup
            // choice, and a failure of one says nothing about the other. Sharing the flag made the
            // last step answer a failed completion with the backup copy, which pointed the user at
            // the wrong control.
            try {
                repository.setCompleted(true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                TelepicLog.warn("OnboardingViewModel", "onboarding could not be completed", throwable)
                _completionFailed.value = true
                return@launch
            }
            // The chosen backup policy is applied **now**, not at the next launch — the same hook the
            // backup-choice path uses, so the engine starts working the moment onboarding ends.
            try {
                onBackupChoiceChanged()
                _completionFailed.value = false
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                TelepicLog.warn(
                    "OnboardingViewModel",
                    "onboarding was completed but the backup choice could not be applied",
                    throwable,
                )
                _completionFailed.value = true
            }
        }
    }

    /**
     * Persist a choice, then let the injected hook apply it — with a boundary around both.
     *
     * The hook runs the app's first real MediaStore scan (and, for a folder choice, a SHA-256 pass
     * over every file in the chosen buckets). It is failure-tolerant in its own right now, but this
     * boundary stays, because anything that escapes `viewModelScope` is an uncaught exception on the
     * main thread: the app closes itself with no message. That is exactly what happened when a scan
     * failed the moment the user confirmed a folder.
     *
     * A failed *persist* is reported as well as caught — the choice did not stick, so the step must
     * not look as if it had accepted it.
     */
    private suspend fun persistChoice(persist: suspend () -> Unit) {
        try {
            persist()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            TelepicLog.warn("OnboardingViewModel", "the backup choice could not be persisted", throwable)
            _setupFailed.value = true
            return
        }
        try {
            onBackupChoiceChanged()
            _setupFailed.value = false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            TelepicLog.warn("OnboardingViewModel", "the backup choice was saved but could not be applied", throwable)
            _setupFailed.value = true
        }
    }
}
