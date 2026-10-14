package com.telepic.ui.screens.settings

import android.app.KeyguardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import com.telepic.data.backup.BackupCoordinator
import com.telepic.data.cloud.CloudRepository
import com.telepic.data.media.AlbumRepository
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudStatus
import com.telepic.domain.media.Album
import com.telepic.onboarding.BackupPreference
import com.telepic.onboarding.OnboardingRepository
import com.telepic.settings.BackupNetworkPreference
import com.telepic.settings.SettingsRepository
import com.telepic.telegram.TelegramAuthController
import com.telepic.telegram.TelegramAuthState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** On-disk footprint of Telepic's caches, in bytes. Never estimated — measured. */
data class StorageSummary(
    val tdlibBytes: Long = 0L,
    val stagingBytes: Long = 0L,
    val loaded: Boolean = false,
) {
    val totalBytes: Long get() = tdlibBytes + stagingBytes
}

/**
 * A transient outcome Settings must report. Types, never magic strings — the screen owns the wording.
 *
 * It exists for the cache actions: their only other visible trace is a size that may not have
 * changed, so a tap that failed (or did nothing) was indistinguishable from one that worked.
 */
sealed interface SettingsMessage {
    /** A cache was cleared successfully. */
    data object CacheCleared : SettingsMessage

    /** An action did not complete. */
    data object ActionFailed : SettingsMessage
}

/**
 * Backs the Settings screen: the real Telegram account state (read from the same controller the
 * auth flow owns — Settings never mirrors it), the persisted backup preference (editable after
 * onboarding, applied immediately through the coordinator's own preference sync), the transport and
 * media preferences, the app-lock switch, measured cache sizes and library counts.
 *
 * Every write goes through [persist], so no row on this screen can take the app down: a tap on a
 * setting is a tap the app must survive, and the preference flows are the single source of truth, so
 * a write that did not land simply re-emits its previous value instead of showing a false change.
 */
class SettingsViewModel(
    private val telegramAuthController: TelegramAuthController,
    private val onboardingRepository: OnboardingRepository,
    private val settingsRepository: SettingsRepository,
    /** The *display* album repository — what the library screens show, used for the library count. */
    private val albumRepository: AlbumRepository? = null,
    /**
     * The *backup-scoped* album repository behind the SELECT_FOLDER picker (see
     * `AppContainer.backupAlbumRepository`). Deliberately separate from [albumRepository]: the picker
     * must list every folder a backup could take — including one whose media the timeline currently
     * hides — and the count beside a folder must match what the backup pass discovers in it, while
     * the library count on this screen must keep describing what the library displays.
     */
    private val foldersRepository: AlbumRepository? = null,
    private val backupCoordinator: BackupCoordinator? = null,
    private val cloudRepository: CloudRepository? = null,
private val appContext: Context,
private val deviceSecureReader: () -> Boolean = {
    appContext.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
},
private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val authState: StateFlow<TelegramAuthState> = telegramAuthController.state

    /**
     * Live Telegram cloud state, read straight from the Cloud layer's own flows (Settings never
     * mirrors or re-derives it). Falls back to [CloudStatus.Initializing] when no repository is
     * supplied (tests), so the connection row is honest rather than absent.
     */
    val cloudStatus: StateFlow<CloudStatus> =
        cloudRepository?.status ?: MutableStateFlow<CloudStatus>(CloudStatus.Initializing)

    /** The resolved Telepic Backup channel title, or null until it is set up. */
    val backupChannelTitle: StateFlow<String?> =
        cloudRepository?.destinationTitle ?: MutableStateFlow<String?>(null)

    val backupPreference: StateFlow<BackupPreference?> =
        onboardingRepository.backupPreference.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val backupBucketIds: StateFlow<Set<Long>> =
        onboardingRepository.backupBucketIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // --- Transport preferences (enforced by WorkManager constraints) ---------------------------

    // Every preference flow below is `Eagerly`, matching [backupPreference] and [backupBucketIds]
    // above. These are cheap DataStore reads that the screen needs the moment it composes, and
    // `WhileSubscribed` would leave `StateFlow.value` stale until something collected it — so any
    // reader of `.value` (a row summary, or a test) would see the previous preference. The VM lives
    // only as long as the Settings destination is on the back stack, so there is nothing to save by
    // deferring the subscription.

    val backupNetworkPreference: StateFlow<BackupNetworkPreference> =
        settingsRepository.backupNetworkPreference
            .stateIn(viewModelScope, SharingStarted.Eagerly, BackupNetworkPreference.Default)

    val backupRequiresCharging: StateFlow<Boolean> =
        settingsRepository.backupRequiresCharging
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val backupRequiresBatteryNotLow: StateFlow<Boolean> =
        settingsRepository.backupRequiresBatteryNotLow
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // --- Media preferences (enforced by the MediaStore query and the video player) --------------

    val showVideosInTimeline: StateFlow<Boolean> =
        settingsRepository.showVideosInTimeline
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val showGifsInTimeline: StateFlow<Boolean> =
        settingsRepository.showGifsInTimeline
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val startVideosMuted: StateFlow<Boolean> =
        settingsRepository.startVideosMuted
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // --- App lock -------------------------------------------------------------------------------

    val appLockEnabled: StateFlow<Boolean> =
        settingsRepository.appLockEnabled
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Whether the device has a PIN, pattern or password configured.
     *
     * The app lock is enforced by the *device's* credential, so with none configured there is nothing
     * to authenticate against. The switch is shown as unavailable rather than letting the user enable
     * a lock that would never fire — a setting that silently does nothing is exactly the kind of claim
     * this codebase avoids making.
     */
    /**
     * Whether the device has a screen-lock credential the app lock can authenticate against.
     *
     * A state rather than a plain value because the credential can be set *while Telepic is open*: the
     * user leaves for system settings, turns a PIN on, and comes back. A value computed once in the
     * constructor kept the row disabled until the process was recreated — telling them the lock still
     * could not fire at the exact moment it first could. [refreshDeviceSecurity] re-reads on resume.
     *
     * [deviceSecureReader] is injected rather than read inline so this is testable, the same pattern
     * `AppLockViewModel` uses for its own credential check.
     */
    private val _isDeviceSecure = MutableStateFlow(deviceSecureReader())
    val isDeviceSecure: StateFlow<Boolean> = _isDeviceSecure.asStateFlow()

    /** Re-reads the device's credential state; the screen calls it when it resumes. */
    fun refreshDeviceSecurity() {
        _isDeviceSecure.value = deviceSecureReader()
    }

    /**
     * Device folders for the folder picker, loaded on demand from the **backup-scoped** repository —
     * the same source onboarding uses, so both pickers offer the same set.
     */
    private val _folders = MutableStateFlow<List<Album>>(emptyList())
    val folders: StateFlow<List<Album>> = _folders.asStateFlow()

    /** False until [loadFolders] finishes, so the picker shows loading — not a false "empty". */
    private val _foldersLoaded = MutableStateFlow(true)
    val foldersLoaded: StateFlow<Boolean> = _foldersLoaded.asStateFlow()

    // --- Library information (local / cloud) ----------------------------------------------------

    /**
     * How many local items the library views currently show, or null while unknown.
     *
     * Summed from the album grouping rather than a second MediaStore count query: that read already
     * applies the same archived/trashed and hide-videos/GIFs filters the timeline does, so this number
     * and the grid cannot disagree. Null means "not measured yet" — never 0, which would be a claim
     * about the user's library that nothing had actually checked.
     */
    private val _localLibraryCount = MutableStateFlow<Int?>(null)
    val localLibraryCount: StateFlow<Int?> = _localLibraryCount.asStateFlow()

    /** False until the local-library measurement finishes, so the row can say "Counting…". */
    private val _localLibraryCountLoaded = MutableStateFlow(false)
    val localLibraryCountLoaded: StateFlow<Boolean> = _localLibraryCountLoaded.asStateFlow()

    /** How many items the Telegram cloud manifest holds, or null when there is no cloud layer. */
    val cloudLibraryCount: StateFlow<Int?> =
        if (cloudRepository == null) {
            MutableStateFlow<Int?>(null)
        } else {
            cloudRepository.media
                .map { media: List<CloudMedia> -> media.size }
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)
        }

    fun loadFolders() {
        _foldersLoaded.value = false
        viewModelScope.launch {
            try {
                _folders.value = foldersRepository?.albums().orEmpty()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Degrade to the picker's own honest empty state instead of letting a provider
                // failure escape `viewModelScope` (an uncaught main-thread exception closes the app).
                _folders.value = emptyList()
            } finally {
                _foldersLoaded.value = true
            }
        }
    }

    fun setBackupChoice(preference: BackupPreference, bucketIds: Set<Long>? = null) {
        persist {
            onboardingRepository.setBackupPreference(preference)
            if (bucketIds != null) onboardingRepository.setBackupBucketIds(bucketIds)
            // Same immediate-apply path onboarding uses; the coordinator gates NOT_NOW itself.
            backupCoordinator?.syncFromPreference()
        }
    }

    fun setBackupNetworkPreference(preference: BackupNetworkPreference) =
        persist { settingsRepository.setBackupNetworkPreference(preference) }

    fun setBackupRequiresCharging(required: Boolean) =
        persist { settingsRepository.setBackupRequiresCharging(required) }

    fun setBackupRequiresBatteryNotLow(required: Boolean) =
        persist { settingsRepository.setBackupRequiresBatteryNotLow(required) }

    /**
     * Hiding videos or GIFs changes what the library queries return, so the timeline's paging source
     * has to be invalidated — otherwise a `cachedIn` pager keeps serving pages built with the old
     * filter until the process restarts. [AlbumRepository.refresh] is the same seam the Viewer uses
     * after archiving, and it invalidates the album/timeline source in one place.
     */
    fun setShowVideosInTimeline(show: Boolean) = persist {
        settingsRepository.setShowVideosInTimeline(show)
        albumRepository?.refresh()
    }

    fun setShowGifsInTimeline(show: Boolean) = persist {
        settingsRepository.setShowGifsInTimeline(show)
        albumRepository?.refresh()
    }

    fun setStartVideosMuted(muted: Boolean) =
        persist { settingsRepository.setStartVideosMuted(muted) }

    fun setAppLockEnabled(enabled: Boolean) =
        persist { settingsRepository.setAppLockEnabled(enabled) }

    fun logout() {
        telegramAuthController.logout()
    }

    /**
     * Manual "back up now": re-runs the same discovery+enqueue path the coordinator uses when
     * backup is enabled, so new media is recognized and queued immediately instead of waiting for
     * the next periodic tick. Runs on the IO dispatcher — a library scan must never touch the main
     * thread. It is a no-op when no automatic backup option is configured (the coordinator gates
     * NOT_NOW itself), which is why the UI disables the row in that case rather than faking a run.
     */
    fun backupNow() {
        val coordinator = backupCoordinator ?: return
        persist { withContext(ioDispatcher) { coordinator.syncFromPreference() } }
    }

    // Paths mirror the real owners: TDLib session under noBackupFilesDir/tdlib
    // (TelegramSessionManager) and staging under cacheDir/telepic_backup_staging (AppContainer).
    private val tdlibDir = File(appContext.noBackupFilesDir, "tdlib")
    private val stagingDir = File(appContext.cacheDir, "telepic_backup_staging")

    private val _storageSummary = MutableStateFlow(StorageSummary())
    val storageSummary: StateFlow<StorageSummary> = _storageSummary.asStateFlow()

    /** Transient feedback for the actions whose only other trace is an unchanged size. */
    private val _message = MutableStateFlow<SettingsMessage?>(null)
    val message: StateFlow<SettingsMessage?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    // Deliberately declared *after* [tdlibDir], [stagingDir] and [_storageSummary]: an `init` block
    // runs in declaration order, so calling refreshStorage() from above them read uninitialized
    // properties. The resulting NPE was then swallowed by [persist], leaving the storage rows stuck at
    // zero with nothing to show for it — a silent wrong answer rather than a visible failure.
    init {
        refreshStorage()
        loadLocalLibraryCount()
    }

    fun refreshStorage() {
        persist {
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
     *
     * Reported, not silent: clearing the image cache in particular has no other visible effect, so a
     * tap that failed (or simply did nothing) was indistinguishable from one that worked.
     */
    fun clearBackupCache() {
        persistReported(SettingsMessage.CacheCleared) {
            withContext(ioDispatcher) {
                stagingDir.listFiles()?.forEach { child -> child.deleteRecursively() }
            }
            refreshStorage()
        }
    }

    /**
     * Drops Coil's decoded-image caches. Both are pure caches: every entry is re-derivable from the
     * media it came from, so clearing can lose nothing. Only the *image* caches are touched — the
     * TDLib session and the backup staging copies are owned elsewhere and are not this row's business.
     */
    fun clearImageCache() {
        persistReported(SettingsMessage.CacheCleared) {
            withContext(ioDispatcher) {
                val loader = appContext.imageLoader
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
        }
    }

    /** Measures the visible local library. Failure leaves the count null — "unknown", never zero. */
    fun loadLocalLibraryCount() {
        val albums = albumRepository
        if (albums == null) {
            _localLibraryCountLoaded.value = true
            return
        }
        viewModelScope.launch {
            try {
                _localLibraryCount.value = withContext(ioDispatcher) {
                    albums.albums().sumOf { it.count }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                _localLibraryCount.value = null
            } finally {
                _localLibraryCountLoaded.value = true
            }
        }
    }

    /**
     * The one exception boundary for every action on this screen.
     *
     * Anything that escapes `viewModelScope` is an uncaught exception on the main thread and the app
     * closes itself with no message. Nothing is claimed when a write fails: the preference flows are
     * observed, so a change that did not persist shows as unchanged rather than as applied.
     */
    private fun persist(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Deliberately silent: the observed preference flow is the honest report.
            }
        }
    }

    /**
     * Like [persist], but the outcome is reported to the user. Used by the cache actions, whose only
     * other trace is a size that may not have changed — so a silent failure looked identical to a
     * successful no-op.
     */
    private fun persistReported(success: SettingsMessage, action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                _message.value = success
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                TelepicLog.warn("SettingsViewModel", "cache action failed", throwable)
                _message.value = SettingsMessage.ActionFailed
            }
        }
    }

    private fun directorySize(root: File): Long =
        if (!root.exists()) 0L
        else root.walkBottomUp().filter { it.isFile }.fold(0L) { acc, file -> acc + file.length() }
}
