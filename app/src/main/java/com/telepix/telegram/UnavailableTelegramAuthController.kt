package com.telepix.telegram

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 2 stand-in: there is no Telegram backend yet, so this controller permanently reports
 * [TelegramAuthState.NotConnected] and never fabricates a connection, account, or channel.
 *
 * Phase 4 replaces this in [com.telepix.di.AppContainer] with a TDLib-backed implementation.
 */
class UnavailableTelegramAuthController : TelegramAuthController {

    private val _state = MutableStateFlow<TelegramAuthState>(TelegramAuthState.NotConnected)

    override val state: StateFlow<TelegramAuthState> = _state.asStateFlow()

    override val isBackendAvailable: Boolean = false

    override fun connect() {
        // No Telegram backend in Phase 2 — intentionally a no-op. UI stays "not connected".
    }

    override fun cancel() {
        // No-op until Phase 4.
    }
}
