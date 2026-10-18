package com.telepic.telegram

import kotlinx.coroutines.flow.StateFlow

/**
 * The UI-facing contract for Telegram authentication. The UI depends only on this interface —
 * never on [org.drinkless.tdlib.Client] directly.
 *
 * Implemented by [TdLibTelegramAuthController] (real). [isBackendAvailable] stays the seam the UI
 * reads to render an honest "unavailable in this build" state instead of faking sign-in — it is
 * exercised by the UI tests through fake controllers.
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

    /** Submit the email address TDLib asked for instead of a phone number. */
    fun submitEmailAddress(emailAddress: String)

    /** Submit the code Telegram emailed. */
    fun submitEmailCode(code: String)

    /** Ask Telegram to email a fresh code (an expired or lost code is otherwise a dead end). */
    fun resendEmailCode()

    fun logout()
}
