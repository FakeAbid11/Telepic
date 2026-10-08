package com.telepix.ui.screens.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepix.data.backup.BackupCoordinator
import com.telepix.data.backup.BackupRepository
import com.telepix.domain.backup.BackupItem
import com.telepix.domain.backup.BackupQueueStats
import com.telepix.domain.backup.BackupState
import com.telepix.telegram.TelegramAuthState
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
) {
    val isEmpty: Boolean get() = items.isEmpty()
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
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    fun startBackup() {
        viewModelScope.launch { coordinator.startPendingBackup() }
    }

    fun retry(itemId: Long) {
        viewModelScope.launch { coordinator.retry(itemId) }
    }

    fun cancel(itemId: Long) {
        viewModelScope.launch { coordinator.cancel(itemId) }
    }
}
