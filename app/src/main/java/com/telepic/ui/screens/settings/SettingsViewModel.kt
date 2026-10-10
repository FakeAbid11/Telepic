package com.telepic.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepic.data.backup.BackupCoordinator
import com.telepic.data.media.AlbumRepository
import com.telepic.domain.media.Album
import com.telepic.onboarding.BackupPreference
import com.telepic.onboarding.OnboardingRepository
import com.telepic.telegram.TelegramAuthController
import com.telepic.telegram.TelegramAuthState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** On-disk footprint of Telepic's caches, in bytes. Never estimated — measured. */
data class StorageSummary(
    val tdlibBytes: Long = 0L,
    val stagingBytes: Long = 0L,
    val loaded: Boolean = false,
) {
    val totalBytes: Long get() = tdlibBytes + stagingBytes
}

/**
 * Backs the Settings screen: the real Telegram account state (read from the same controller the
 * auth flow owns — Settings never mirrors it), the persisted backup preference (editable after
 * onboarding, applied immediately through the coordinator's own preference sync), and measured
 * cache sizes. Sign-out delegates to [TelegramAuthController.logout] only; wiping the local TDLib
 * database on logout is a known follow-up, so this screen never claims the session data is gone.
 */
class SettingsViewModel(
    private val telegramAuthController: TelegramAuthController,
    private val onboardingRepository: OnboardingRepository,
    private val albumRepository: AlbumRepository? = null,
    private val backupCoordinator: BackupCoordinator? = null,
    appContext: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val authState: StateFlow<TelegramAuthState> = telegramAuthController.state

    val backupPreference: StateFlow<BackupPreference?> =
        onboardingRepository.backupPreference.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val backupBucketIds: StateFlow<Set<Long>> =
        onboardingRepository.backupBucketIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Device folders for the folder picker, loaded on demand (same source as onboarding). */
    private val _folders = MutableStateFlow<List<Album>>(emptyList())
    val folders: StateFlow<List<Album>> = _folders.asStateFlow()

    fun loadFolders() {
        viewModelScope.launch {
            _folders.value = albumRepository?.albums().orEmpty()
        }
    }

    fun setBackupChoice(preference: BackupPreference, bucketIds: Set<Long>? = null) {
        viewModelScope.launch {
            onboardingRepository.setBackupPreference(preference)
            if (bucketIds != null) onboardingRepository.setBackupBucketIds(bucketIds)
            // Same immediate-apply path onboarding uses; the coordinator gates NOT_NOW itself.
            backupCoordinator?.syncFromPreference()
        }
    }

    fun logout() {
        telegramAuthController.logout()
    }

    // Paths mirror the real owners: TDLib session under noBackupFilesDir/tdlib
    // (TelegramSessionManager) and staging under cacheDir/telepic_backup_staging (AppContainer).
    private val tdlibDir = File(appContext.noBackupFilesDir, "tdlib")
    private val stagingDir = File(appContext.cacheDir, "telepic_backup_staging")

    private val _storageSummary = MutableStateFlow(StorageSummary())
    val storageSummary: StateFlow<StorageSummary> = _storageSummary.asStateFlow()

    init {
        refreshStorage()
    }

    fun refreshStorage() {
        viewModelScope.launch {
            val summary = withContext(ioDispatcher) {
                StorageSummary(
                    tdlibBytes = directorySize(tdlibDir),
                    stagingBytes = directorySize(stagingDir),
                    loaded = true,
                )
            }
            _storageSummary.value = summary
        }
    }

    /**
     * Empties only the staging cache — transient upload copies that the engine recreates on demand.
     * The TDLib session/cache is never touched from here: deleting it could orphan the encrypted
     * database, and that is sign-out's (not this button's) semantic.
     */
    fun clearBackupCache() {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                stagingDir.listFiles()?.forEach { child -> child.deleteRecursively() }
            }
            refreshStorage()
        }
    }

    private fun directorySize(root: File): Long =
        if (!root.exists()) 0L
        else root.walkBottomUp().filter { it.isFile }.fold(0L) { acc, file -> acc + file.length() }
}
