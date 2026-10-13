package com.telepic.telegram

import org.drinkless.tdlib.TdApi

/**
 * Maps a TDLib [TdApi.AuthorizationState] onto the app's [TelegramAuthState].
 *
 * Handles every authorization state TDLib can report — not just the common ones — so unexpected
 * states degrade to [TelegramAuthState.Initializing] rather than crashing or faking readiness.
 * [Ready] only becomes [TelegramAuthState.Authorized] once the account is actually known
 * ([authorizedUser]); until then it stays [TelegramAuthState.Initializing].
 *
 * The email variants ([TdApi.AuthorizationStateWaitEmailAddress],
 * [TdApi.AuthorizationStateWaitEmailCode], [TdApi.AuthorizationStateWaitPremiumPurchase]) each get
 * their own state. They used to fall through to the `else` branch and render as
 * [TelegramAuthState.Initializing] — a spinner with no input and no button, which is a permanent
 * dead end for an account that signs in by email.
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
        is TdApi.AuthorizationStateWaitEmailAddress -> TelegramAuthState.WaitingForEmailAddress()
        is TdApi.AuthorizationStateWaitEmailCode -> TelegramAuthState.WaitingForEmailCode(
            // TDLib's own masked rendering of the destination; null when the wait carries no info
            // yet, which the UI renders as "your email" rather than inventing an address.
            emailPattern = state.codeInfo?.emailAddressPattern?.takeIf { it.isNotBlank() },
            codeLength = state.codeInfo?.length ?: 0,
        )
        is TdApi.AuthorizationStateWaitPremiumPurchase -> TelegramAuthState.WaitingForPremiumPurchase(
            supportEmailAddress = state.supportEmailAddress?.takeIf { it.isNotBlank() },
            supportEmailSubject = state.supportEmailSubject?.takeIf { it.isNotBlank() },
        )
        is TdApi.AuthorizationStateWaitRegistration -> TelegramAuthState.WaitingForRegistration
        is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
            TelegramAuthState.WaitingForOtherDeviceConfirmation(state.link)
        is TdApi.AuthorizationStateReady ->
            authorizedUser?.let { TelegramAuthState.Authorized(it) } ?: TelegramAuthState.Initializing
        is TdApi.AuthorizationStateLoggingOut -> TelegramAuthState.LoggingOut
        is TdApi.AuthorizationStateClosing -> TelegramAuthState.Closing
        is TdApi.AuthorizationStateClosed -> TelegramAuthState.Closed
        else -> TelegramAuthState.Initializing
    }
}
