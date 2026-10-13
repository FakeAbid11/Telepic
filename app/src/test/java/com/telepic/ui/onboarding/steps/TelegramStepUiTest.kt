package com.telepic.ui.onboarding.steps

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
        var submittedEmail: String? = null
        var submittedEmailCode: String? = null
        var resendCount = 0
        override fun start() { startCount++ }
        override fun submitPhoneNumber(phoneNumber: String) { submittedPhone = phoneNumber }
        override fun submitCode(code: String) { submittedCode = code }
        override fun submitPassword(password: String) { submittedPassword = password }
        override fun submitEmailAddress(emailAddress: String) { submittedEmail = emailAddress }
        override fun submitEmailCode(code: String) { submittedEmailCode = code }
        override fun resendEmailCode() { resendCount++ }
        var logoutCount = 0
        override fun logout() { logoutCount++ }
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

    @Test
    fun `other-device confirmation surfaces the link and never dead-ends`() {
        val controller = FakeController(isBackendAvailable = true)
        render(
            controller,
            TelegramAuthState.WaitingForOtherDeviceConfirmation("tg://login?token=abc123"),
        )
        composeRule
            .onNodeWithText("Approve the login request on your other Telegram device.")
            .assertIsDisplayed()
        // The link TDLib produced has to actually reach the user, wired to a real action...
        composeRule.onNodeWithText("Open in Telegram").assertIsDisplayed().assertHasClickAction()
        // ...and the state must always offer a way out instead of stranding the user here.
        composeRule.onNodeWithText("Use a different account").performClick()
        assertEquals(1, controller.logoutCount)
    }

    // --- Email sign-in ---------------------------------------------------------------------
    //
    // These three TDLib states used to fall through the mapper to `Initializing`, which renders a
    // spinner with no input and no button. For an account that signs in by email that is a permanent
    // dead end, so each one is pinned here as a real, escapable step.

    @Test
    fun `email address state shows an email input and submits to the controller`() {
        val controller = FakeController(isBackendAvailable = true)
        render(controller, TelegramAuthState.WaitingForEmailAddress())
        composeRule.onNodeWithText("Email address").assertIsDisplayed()
        // Disabled until something address-shaped is entered — Telegram is the real validator.
        composeRule.onNodeWithText("Send code").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextInput("abid@example.com")
        composeRule.onNodeWithText("Send code").performClick()
        assertEquals("abid@example.com", controller.submittedEmail)
    }

    @Test
    fun `a rejected email address stays on the field and shows the reason inline`() {
        render(
            FakeController(isBackendAvailable = true),
            TelegramAuthState.WaitingForEmailAddress(error = "The email address is invalid."),
        )
        composeRule.onNodeWithText("Email address").assertIsDisplayed()
        composeRule.onNodeWithText("The email address is invalid.").assertIsDisplayed()
    }

    @Test
    fun `email code state shows the masked address and submits the code`() {
        val controller = FakeController(isBackendAvailable = true)
        render(
            controller,
            TelegramAuthState.WaitingForEmailCode(emailPattern = "a***@gmail.com", codeLength = 5),
        )
        // TDLib's own masked address, shown as-is rather than reconstructed.
        composeRule.onNodeWithText("We sent a code to a***@gmail.com").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextInput("12345")
        composeRule.onNodeWithText("Sign in").performClick()
        assertEquals("12345", controller.submittedEmailCode)
    }

    @Test
    fun `the email code submit waits for the length Telegram reported`() {
        val controller = FakeController(isBackendAvailable = true)
        render(
            controller,
            TelegramAuthState.WaitingForEmailCode(emailPattern = "a***@gmail.com", codeLength = 5),
        )
        composeRule.onNodeWithText("Sign in").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextInput("123")
        composeRule.onNodeWithText("Sign in").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextInput("45")
        composeRule.onNodeWithText("Sign in").assertIsEnabled()
    }

    @Test
    fun `an unknown code length falls back to a non-empty check rather than blocking`() {
        render(
            FakeController(isBackendAvailable = true),
            // codeLength = 0 means TDLib did not say; the button must still become usable.
            TelegramAuthState.WaitingForEmailCode(emailPattern = null, codeLength = 0),
        )
        composeRule.onNodeWithText("We sent a code to your email address").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextInput("9")
        composeRule.onNodeWithText("Sign in").assertIsEnabled()
    }

    @Test
    fun `an email code can be resent, and the state never dead-ends`() {
        val controller = FakeController(isBackendAvailable = true)
        render(
            controller,
            TelegramAuthState.WaitingForEmailCode(emailPattern = "a***@gmail.com", codeLength = 5),
        )
        composeRule.onNodeWithText("Resend code").assertHasClickAction().performClick()
        assertEquals(1, controller.resendCount)
        composeRule.onNodeWithText("Use a different account").performClick()
        assertEquals(1, controller.logoutCount)
    }

    @Test
    fun `premium purchase is reported honestly and never dead-ends`() {
        val controller = FakeController(isBackendAvailable = true)
        render(
            controller,
            TelegramAuthState.WaitingForPremiumPurchase(
                supportEmailAddress = "premium@telegram.org",
                supportEmailSubject = "Premium",
            ),
        )
        // Said plainly, not hidden behind a spinner that never resolves.
        composeRule
            .onNodeWithText(
                "This account has to complete a Telegram Premium purchase before it can sign in, " +
                    "and Telepic cannot make that purchase for you. Please finish it in the official " +
                    "Telegram app, then sign in here.",
            )
            .assertIsDisplayed()
        composeRule.onNodeWithText("Contact Telegram support").assertHasClickAction()
        composeRule.onNodeWithText("Use a different account").performClick()
        assertEquals(1, controller.logoutCount)
    }
}
