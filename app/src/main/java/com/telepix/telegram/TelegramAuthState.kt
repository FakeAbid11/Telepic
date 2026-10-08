package com.telepix.telegram

/**
 * The Telegram authorization lifecycle the UI can render.
 *
 * This is the state *contract* only. Phase 2 never produces anything other than
 * [NotConnected]; Phase 4 maps the real TDLib authorization state
 * (`org.drinkless.tdlib.TdApi.AuthorizationState`) onto these values.
 */
sealed interface TelegramAuthState {
    /** No Telegram backend is connected. The Phase 2 default. */
    data object NotConnected : TelegramAuthState

    data object Connecting : TelegramAuthState

    data object WaitingForPhoneNumber : TelegramAuthState

    data object WaitingForCode : TelegramAuthState

    data object WaitingForPassword : TelegramAuthState

    data object Authorized : TelegramAuthState

    data class Error(val message: String) : TelegramAuthState
}
