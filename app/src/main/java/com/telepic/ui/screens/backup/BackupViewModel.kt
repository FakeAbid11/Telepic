package com.telepic.ui.screens.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepic.data.backup.BackupCoordinator
import com.telepic.data.backup.BackupRepository
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.backup.BackupItem
import com.telepic.domain.backup.BackupQueueStats
import com.telepic.domain.backup.BackupState
import com.telepic.telegram.TelegramAuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A transient outcome the Backup Center must report.
 *
 * Types, never magic strings, so the screen owns the wording. It exists because every action on this
 * screen used to fail silently: the row was left unchanged, so a failed retry/cancel/start was
 * indistinguishable from a tap that missed.
 */
sealed interface BackupMessage {
    /** A retry / cancel / start tap did not complete. Nothing is claimed about the row. */
    data object ActionFailed : BackupMessage
}

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
    /**
     * The queue read failed outright. Distinct from "still loading": a failed read must not leave the
     * screen on its skeleton forever, which is indistinguishable from a hang — it gets an honest
     * error and a retry.
     */
    val loadFailed: Boolean = false,
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
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModel(
    private val coordinator: BackupCoordinator,
    authState: kotlinx.coroutines.flow.StateFlow<TelegramAuthState>,
) : ViewModel() {

    private val repository: BackupRepository = coordinator.repository

    /** Bumped by [retryLoad] to re-subscribe after the queue flow failed. */
    private val reloadTrigger = MutableStateFlow(0)

    val uiState: StateFlow<BackupUiState> = reloadTrigger
        .flatMapLatest {
            combine(repository.observeQueue(), repository.observeStats(), authState) { items, stats, auth ->
                BackupUiState(
                    stats = stats,
                    items = items,
                    isAuthorized = auth is TelegramAuthState.Authorized,
                    isLoaded = true,
                )
            }.catch {
                // A failed queue read must not leave the screen on its skeleton forever — no message,
                // no way out. Report it honestly and let [retryLoad] re-subscribe.
                TelepicLog.warn("BackupViewModel", "could not read the backup queue", it)
                emit(BackupUiState(isLoaded = true, loadFailed = true))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    private val _message = MutableStateFlow<BackupMessage?>(null)
    val message: StateFlow<BackupMessage?> = _message.asStateFlow()

    /**
     * Bumped on every emission so the transient bar can tell a repeated failure apart from a stale
     * one. [BackupMessage.ActionFailed] is a single object, so a second failed tap is indistinguishable
     * from the first by value — without this token its bar would keep the first event's expiring timer.
     */
    private val _messageId = MutableStateFlow(0L)
    val messageId: StateFlow<Long> = _messageId.asStateFlow()

    /** Re-read the queue after [BackupUiState.loadFailed]. */
    fun retryLoad() {
        reloadTrigger.update { it + 1 }
    }

    fun consumeMessage() {
        _message.value = null
    }

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
     * one fails: the queue rows are simply left as they are, still observed, still re-tryable — but
     * the failure is now *reported*, because an unchanged row read as a missed tap.
     */
    private suspend fun runAction(action: suspend () -> Unit) {
        try {
            action()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            TelepicLog.warn("BackupViewModel", "backup action failed", throwable)
            _messageId.value += 1
            _message.value = BackupMessage.ActionFailed
        }
    }
}
