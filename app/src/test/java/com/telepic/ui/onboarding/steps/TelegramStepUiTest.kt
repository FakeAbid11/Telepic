package com.telepic.ui.onboarding.steps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.telepic.telegram.TelegramAuthController
import com.telepic.telegram.TelegramAuthState
import com.telepic.telegram.TelegramError
import com.telepic.telegram.TelegramUser
import com.telepic.ui.theme.TelepicTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose UI tests for the real Telegram authentication step. A fake controller feeds
 * each state, verifying the correct input appears per TDLib state, that submission reaches the
 * controller, and that the UI never shows a fake "connected" state without authorization.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class TelegramStepUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeController(
        override val isBackendAvailable: Boolean,
    ) : TelegramAuthController {
        private val flow = MutableStateFlow<TelegramAuthState>(TelegramAuthState.NotConnected)
        override val state: StateFlow<TelegramAuthState> = flow.asStateFlow()
        var startCount = 0
        var submittedPhone: String? = null
        var submittedCode: String? = null
        var submittedPassword: String? = null
        override fun start() { startCount++ }
        override fun submitPhoneNumber(phoneNumber: String) { submittedPhone = phoneNumber }
        override fun submitCode(code: String) { submittedCode = code }
        override fun submitPassword(password: String) { submittedPassword = password }
        override fun logout() = Unit
    }

    private fun render(
        controller: TelegramAuthController,
        state: TelegramAuthState,
        onContinue: () -> Unit = {},
    ) {
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                TelegramStep(controller = controller, state = state, onContinue = onContinue)
            }
        }
    }

    @Test
    fun `initializing shows connecting without any success`() {
        render(FakeController(isBackendAvailable = true), TelegramAuthState.Initializing)
        composeRule.onNodeWithText("Connecting to Telegram…").assertIsDisplayed()
        composeRule.onNodeWithText("Connected as").assertDoesNotExist()
    }

    @Test
    fun `phone state shows input and submits to the controller`() {
        val controller = FakeController(isBackendAvailable = true)
        render(controller, TelegramAuthState.WaitingForPhoneNumber())
        composeRule.onNodeWithText("Phone number").assertIsDisplayed()
        // Send code is disabled until a number is entered.
        composeRule.onNodeWithText("Send code").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextInput("+15550101234")
        composeRule.onNodeWithText("Send code").performClick()
        assertEquals("+15550101234", controller.submittedPhone)
    }

    @Test
    fun `code state shows a code input`() {
        render(FakeController(isBackendAvailable = true), TelegramAuthState.WaitingForCode())
        composeRule.onNodeWithText("Confirmation code").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertIsDisplayed()
    }

    @Test
    fun `a wrong code stays on the code field and shows the reason inline`() {
        render(
            FakeController(isBackendAvailable = true),
            TelegramAuthState.WaitingForCode(error = "The code is invalid."),
        )
        // Not a dead-end: the code input is still present AND the reason is shown.
        composeRule.onNodeWithText("Confirmation code").assertIsDisplayed()
        composeRule.onNodeWithText("The code is invalid.").assertIsDisplayed()
    }

    @Test
    fun `password state shows a password input`() {
        render(FakeController(isBackendAvailable = true), TelegramAuthState.WaitingForPassword())
        composeRule.onNodeWithText("Two-factor password").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertIsDisplayed()
    }

    @Test
    fun `authorized shows the account name and continues`() {
        var continued = false
        val state = TelegramAuthState.Authorized(TelegramUser(1L, "Abid", "Hossain", "abid"))
        render(FakeController(isBackendAvailable = true), state, onContinue = { continued = true })
        composeRule.onNodeWithText("Connected as Abid Hossain").assertIsDisplayed()
        composeRule.onNodeWithText("Continue").performClick()
        assertTrue(continued)
    }

    @Test
    fun `error shows a message and retry restarts the controller`() {
        val controller = FakeController(isBackendAvailable = true)
        render(controller, TelegramAuthState.Failed(TelegramError.InvalidCode("The code is invalid.")))
        composeRule.onNodeWithText("The code is invalid.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, controller.startCount)
    }

    @Test
    fun `unavailable backend shows not connected with no fake success`() {
        render(FakeController(isBackendAvailable = false), TelegramAuthState.NotConnected)
        composeRule.onNodeWithText("Not connected").assertIsDisplayed()
        // No interactive auth UI when the backend is unavailable.
        composeRule.onNodeWithText("Phone number").assertDoesNotExist()
        composeRule.onNodeWithText("Connected as").assertDoesNotExist()
    }
}
