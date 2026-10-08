package com.telepix.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Phase 2 honesty contract: with no Telegram backend wired up, the controller must
 * report not-connected, expose it as unavailable, and never fabricate a connection.
 */
class UnavailableTelegramAuthControllerTest {

    @Test
    fun `starts in the not-connected state`() {
        val controller = UnavailableTelegramAuthController()
        assertTrue(controller.state.value is TelegramAuthState.NotConnected)
    }

    @Test
    fun `reports the backend as unavailable`() {
        val controller = UnavailableTelegramAuthController()
        assertFalse(controller.isBackendAvailable)
    }

    @Test
    fun `start never fakes an authorized state`() {
        val controller = UnavailableTelegramAuthController()
        controller.start()
        controller.submitPhoneNumber("+10000000000")
        controller.submitCode("12345")
        controller.logout()
        assertTrue(controller.state.value is TelegramAuthState.NotConnected)
    }
}
