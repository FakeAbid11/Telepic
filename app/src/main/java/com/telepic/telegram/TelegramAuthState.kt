package com.telepic.telegram

/**
 * The Telegram authentication states the UI renders. This is the same contract Phase 2 defined
 * (so existing screens/tests keep compiling), now produced for real by the TDLib backend
 * ([TdLibTelegramAuthController]) via [AuthorizationStateMapper] instead of a stub.
 *
 * [NotConnected] remains the honest initial/unavailable state — nothing here is faked.
 */
sealed interface TelegramAuthState {
    /** No session started yet, or the backend is unavailable. */
    data object NotConnected : TelegramAuthState

    data object Initializing : TelegramAuthState

    /** Waiting for the phone number. [error] is a recoverable message shown inline while staying here. */
    data class WaitingForPhoneNumber(val error: String? = null) : TelegramAuthState

    /** Waiting for the SMS code. A wrong code keeps this state and surfaces [error] — not a dead-end. */
    data class WaitingForCode(val error: String? = null) : TelegramAuthState

    /** Waiting for the 2FA password. A wrong password keeps this state and surfaces [error]. */
    data class WaitingForPassword(val error: String? = null) : TelegramAuthState

    /**
     * Waiting for an **email address** instead of a phone number (TDLib
     * `AuthorizationStateWaitEmailAddress`). Telegram asks for this when the account was registered
     * by email or when the number route is unavailable; a rejected address keeps this state and
     * surfaces [error] rather than becoming a dead end.
     */
    data class WaitingForEmailAddress(val error: String? = null) : TelegramAuthState

    /**
     * Waiting for the code Telegram emailed (TDLib `AuthorizationStateWaitEmailCode`).
     *
     * [emailPattern] is TDLib's own masked rendering of the destination (e.g. `a***@gmail.com`) —
     * shown as-is, never reconstructed, so the UI cannot claim an address it does not know.
     * [codeLength] is the length Telegram expects, used to gate the submit button; 0 means unknown.
     */
    data class WaitingForEmailCode(
        val emailPattern: String? = null,
        val codeLength: Int = 0,
        val error: String? = null,
    ) : TelegramAuthState

    /**
     * TDLib requires a Telegram Premium purchase to finish this login
     * (`AuthorizationStateWaitPremiumPurchase`). Telepic cannot make that purchase — it is not a
     * Telegram client with a store front — so this is reported honestly, with the support contact
     * TDLib supplies, instead of spinning forever.
     */
    data class WaitingForPremiumPurchase(
        val supportEmailAddress: String? = null,
        val supportEmailSubject: String? = null,
    ) : TelegramAuthState

    data class WaitingForOtherDeviceConfirmation(val link: String) : TelegramAuthState

    data object WaitingForRegistration : TelegramAuthState

    data class Authorized(val user: TelegramUser) : TelegramAuthState

    data object LoggingOut : TelegramAuthState

    data object Closing : TelegramAuthState

    data object Closed : TelegramAuthState

    data class Failed(val error: TelegramError) : TelegramAuthState

    companion object {
        /** Whether authentication has completed successfully. */
        fun TelegramAuthState.isAuthorized(): Boolean = this is Authorized

        /** Whether a fresh login flow can be started from this state. */
        fun TelegramAuthState.canStartLogin(): Boolean =
            this is NotConnected || this is Failed || this is Closed
    }
}
