package com.telepic.telegram

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fallback controller used only when no Telegram backend is wired (e.g. native library missing).
 * It permanently reports [TelegramAuthState.NotConnected] and never fakes a connection, account,
 * or channel. [TdLibTelegramAuthController] is the real Phase 4 implementation.
 */
class UnavailableTelegramAuthController : TelegramAuthController {

    private val _state = MutableStateFlow<TelegramAuthState>(TelegramAuthState.NotConnected)
    override val state: StateFlow<TelegramAuthState> = _state.asStateFlow()

    override val isBackendAvailable: Boolean = false

    override fun start() = Unit
    override fun submitPhoneNumber(phoneNumber: String) = Unit
    override fun submitCode(code: String) = Unit
    override fun submitPassword(password: String) = Unit
    override fun logout() = Unit
}
