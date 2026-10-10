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
