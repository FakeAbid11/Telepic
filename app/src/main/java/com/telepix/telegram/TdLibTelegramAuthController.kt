package com.telepix.telegram

import kotlinx.coroutines.flow.StateFlow

/**
 * The real [TelegramAuthController]: adapts [TelegramSessionManager] to the UI contract. The UI
 * never sees TDLib directly. All authentication results come from TDLib — nothing is faked.
 */
class TdLibTelegramAuthController(
    private val sessionManager: TelegramSessionManager,
) : TelegramAuthController {

    override val state: StateFlow<TelegramAuthState> = sessionManager.state

    override val isBackendAvailable: Boolean = true

    override fun start() = sessionManager.initialize()

    override fun submitPhoneNumber(phoneNumber: String) = sessionManager.submitPhoneNumber(phoneNumber)

    override fun submitCode(code: String) = sessionManager.submitCode(code)

    override fun submitPassword(password: String) = sessionManager.submitPassword(password)

    override fun logout() = sessionManager.logout()
}
