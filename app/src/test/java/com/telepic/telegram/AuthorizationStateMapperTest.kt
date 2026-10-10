package com.telepic.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.drinkless.tdlib.TdApi

/**
 * Verifies every TDLib authorization state maps to the correct app state, including that
 * [TelegramAuthState.Authorized] only appears once the account is actually known. TdApi types
 * are plain Java, so this runs on the JVM without a native library.
 */
class AuthorizationStateMapperTest {

    private val user = TelegramUser(42L, "Abid", "Hossain", "abid")

    @Test
    fun `null maps to not connected`() {
        assertEquals(TelegramAuthState.NotConnected, AuthorizationStateMapper.map(null))
    }

    @Test
    fun `phone code password map directly`() {
        assertEquals(
            TelegramAuthState.WaitingForPhoneNumber(),
            AuthorizationStateMapper.map(TdApi.AuthorizationStateWaitPhoneNumber()),
        )
        assertEquals(
            TelegramAuthState.WaitingForCode(),
            AuthorizationStateMapper.map(TdApi.AuthorizationStateWaitCode()),
        )
        assertEquals(
            TelegramAuthState.WaitingForPassword(),
            AuthorizationStateMapper.map(TdApi.AuthorizationStateWaitPassword()),
        )
    }

    @Test
    fun `setup states map to initializing`() {
        assertEquals(
            TelegramAuthState.Initializing,
            AuthorizationStateMapper.map(TdApi.AuthorizationStateWaitTdlibParameters()),
        )
    }

    @Test
    fun `registration and other-device map explicitly`() {
        assertEquals(
            TelegramAuthState.WaitingForRegistration,
            AuthorizationStateMapper.map(TdApi.AuthorizationStateWaitRegistration()),
        )
        val other = TdApi.AuthorizationStateWaitOtherDeviceConfirmation().apply { link = "tg://confirm?code=1" }
        assertEquals(
            TelegramAuthState.WaitingForOtherDeviceConfirmation("tg://confirm?code=1"),
            AuthorizationStateMapper.map(other),
        )
    }

    @Test
    fun `closing and closed map`() {
        assertEquals(
            TelegramAuthState.Closing,
            AuthorizationStateMapper.map(TdApi.AuthorizationStateClosing()),
        )
        assertEquals(
            TelegramAuthState.Closed,
            AuthorizationStateMapper.map(TdApi.AuthorizationStateClosed()),
        )
    }

    @Test
    fun `ready only authorizes once the account is known`() {
        // Without the account yet, Ready is still "initializing" (never fakes Authorized).
        assertEquals(
            TelegramAuthState.Initializing,
            AuthorizationStateMapper.map(TdApi.AuthorizationStateReady(), authorizedUser = null),
        )
        val authorized = AuthorizationStateMapper.map(TdApi.AuthorizationStateReady(), user)
        assertTrue(authorized is TelegramAuthState.Authorized)
        assertEquals(user, (authorized as TelegramAuthState.Authorized).user)
    }
}
