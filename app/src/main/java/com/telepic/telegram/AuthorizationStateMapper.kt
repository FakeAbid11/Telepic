package com.telepic.telegram

import org.drinkless.tdlib.TdApi

/**
 * Maps a TDLib [TdApi.AuthorizationState] onto the app's [TelegramAuthState].
 *
 * Handles every authorization state TDLib can report — not just the common ones — so unexpected
 * states degrade to [TelegramAuthState.Initializing] rather than crashing or faking readiness.
 * [Ready] only becomes [TelegramAuthState.Authorized] once the account is actually known
 * ([authorizedUser]); until then it stays [TelegramAuthState.Initializing].
 */
object AuthorizationStateMapper {

    fun map(
        state: TdApi.AuthorizationState?,
        authorizedUser: TelegramUser? = null,
    ): TelegramAuthState = when (state) {
        null -> TelegramAuthState.NotConnected
        is TdApi.AuthorizationStateWaitTdlibParameters -> TelegramAuthState.Initializing
        is TdApi.AuthorizationStateWaitPhoneNumber -> TelegramAuthState.WaitingForPhoneNumber()
        is TdApi.AuthorizationStateWaitCode -> TelegramAuthState.WaitingForCode()
        is TdApi.AuthorizationStateWaitPassword -> TelegramAuthState.WaitingForPassword()
        is TdApi.AuthorizationStateWaitRegistration -> TelegramAuthState.WaitingForRegistration
        is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
            TelegramAuthState.WaitingForOtherDeviceConfirmation(state.link)
        is TdApi.AuthorizationStateReady ->
            authorizedUser?.let { TelegramAuthState.Authorized(it) } ?: TelegramAuthState.Initializing
        is TdApi.AuthorizationStateClosing -> TelegramAuthState.Closing
        is TdApi.AuthorizationStateClosed -> TelegramAuthState.Closed
        else -> TelegramAuthState.Initializing
    }
}
