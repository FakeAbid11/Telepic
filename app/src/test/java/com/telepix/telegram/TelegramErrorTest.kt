package com.telepix.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies TDLib error codes/messages map to safe, useful [TelegramError] types. */
class TelegramErrorTest {

    @Test
    fun `flood wait parses the retry delay`() {
        val error = TelegramError.classify(420, "Too many attempts, please try again in 37 seconds")
        assertTrue(error is TelegramError.FloodWait)
        assertEquals(37, (error as TelegramError.FloodWait).retryAfterSeconds)
    }

    @Test
    fun `unauthorized maps to 401`() {
        assertTrue(TelegramError.classify(401, "Unauthorized") is TelegramError.Unauthorized)
    }

    @Test
    fun `400 uses the stage hint to name the field`() {
        assertTrue(
            TelegramError.classify(400, "bad", TelegramError.AuthStage.PhoneNumber)
                is TelegramError.InvalidPhoneNumber,
        )
        assertTrue(
            TelegramError.classify(400, "bad", TelegramError.AuthStage.Code)
                is TelegramError.InvalidCode,
        )
        assertTrue(
            TelegramError.classify(400, "bad", TelegramError.AuthStage.Password)
                is TelegramError.InvalidPassword,
        )
        assertTrue(TelegramError.classify(400, "bad") is TelegramError.Unknown)
    }

    @Test
    fun `network problems are detected from code and message`() {
        assertTrue(TelegramError.classify(499, "oops") is TelegramError.Network)
        assertTrue(
            TelegramError.classify(500, "NETWORK_CALL_FAILED") is TelegramError.Network,
        )
    }

    @Test
    fun `unknown codes fall back to unknown`() {
        assertTrue(TelegramError.classify(404, "nope") is TelegramError.Unknown)
    }

    @Test
    fun `messages are sanitized and never blank`() {
        val error = TelegramError.classify(400, "   ")
        assertTrue(error.message.isNotBlank())
    }
}
