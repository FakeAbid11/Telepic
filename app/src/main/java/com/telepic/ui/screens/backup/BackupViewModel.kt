package com.telepic.ui.screens.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepic.data.backup.BackupCoordinator
import com.telepic.data.backup.BackupRepository
import com.telepic.domain.backup.BackupItem
import com.telepic.domain.backup.BackupQueueStats
import com.telepic.domain.backup.BackupState
import com.telepic.telegram.TelegramAuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Immutable Backup Center state. */
data class BackupUiState(
    val stats: BackupQueueStats = BackupQueueStats(),
    val items: List<BackupItem> = emptyList(),
    val isAuthorized: Boolean = false,
    /**
     * False until the queue has actually been read. The initial value cannot tell "nothing queued"
     * from "not read yet", so without this the screen asserted "no backups yet" — a claim about the
     * user's data — for the frame before Room's first emission, over a queue that had rows in it.
     */
    val isLoaded: Boolean = false,
) {
    /** Genuinely nothing queued — never true before the queue has been read. */
    val isEmpty: Boolean get() = isLoaded && items.isEmpty()
    val hasWaitingForAuth: Boolean get() = items.any { it.state == BackupState.WAITING_FOR_AUTH }
    val hasWaitingForNetwork: Boolean get() = items.any { it.state == BackupState.WAITING_FOR_NETWORK }
    val isRunning: Boolean get() = stats.uploading > 0
}

/**
 * Owns Backup Center state and the queue actions. All work is delegated to [BackupCoordinator] /
 * [BackupRepository]; nothing here touches Room, WorkManager or TDLib directly.
 */
class BackupViewModel(
    private val coordinator: BackupCoordinator,
    authState: kotlinx.coroutines.flow.StateFlow<TelegramAuthState>,
) : ViewModel() {

    private val repository: BackupRepository = coordinator.repository

    val uiState: StateFlow<BackupUiState> =
        combine(repository.observeQueue(), repository.observeStats(), authState) { items, stats, auth ->
            BackupUiState(
                stats = stats,
                items = items,
                isAuthorized = auth is TelegramAuthState.Authorized,
                isLoaded = true,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    fun startBackup() {
        viewModelScope.launch { runAction { coordinator.startPendingBackup() } }
    }

    fun retry(itemId: Long) {
        viewModelScope.launch { runAction { coordinator.retry(itemId) } }
    }

    fun cancel(itemId: Long) {
        viewModelScope.launch { runAction { coordinator.cancel(itemId) } }
    }

    /**
     * Every action on this screen is a tap the app must survive. Anything that escapes
     * `viewModelScope` is an uncaught exception on the main thread — the app closes itself — so a
     * database or scheduling failure here must not be allowed to propagate. Nothing is claimed when
     * one fails: the queue rows are simply left as they are, still observed, still re-tryable.
     */
    private suspend fun runAction(action: suspend () -> Unit) {
        try {
            action()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Deliberately silent at this layer: the queue flow is the honest report, and the row
            // the user acted on is still visible with its unchanged state.
        }
    }
}
