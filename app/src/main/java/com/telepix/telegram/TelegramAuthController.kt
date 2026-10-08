package com.telepix.telegram

import kotlinx.coroutines.flow.StateFlow

/**
 * The UI-facing contract for Telegram authentication. The UI depends only on this interface —
 * never on [org.drinkless.tdlib.Client] directly.
 *
 * Phase 4 provides [TdLibTelegramAuthController] (real). [UnavailableTelegramAuthController]
 * remains as a fallback when no backend is wired, and never fakes success.
 */
interface TelegramAuthController {
    val state: StateFlow<TelegramAuthState>

    /** Whether a real Telegram backend is available. False for the fallback implementation. */
    val isBackendAvailable: Boolean

    /** Begin/restore the authentication session (idempotent). */
    fun start()

    fun submitPhoneNumber(phoneNumber: String)

    fun submitCode(code: String)

    fun submitPassword(password: String)

    fun logout()
}
