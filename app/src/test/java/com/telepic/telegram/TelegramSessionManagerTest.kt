package com.telepic.telegram

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The authorization-startup flow must (a) never lose the first `UpdateAuthorizationState` emitted at
 * open(), and (b) be re-attemptable after a failure — the onboarding "Try again" button calls
 * `initialize()` again, and because TDLib does not re-announce `WaitTdlibParameters` after a rejected
 * `SetTdlibParameters`, the retry must re-drive the parameters itself (previously it was a no-op).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TelegramSessionManagerTest {

    private class FakeGateway(
        // What GetAuthorizationState reports — a fresh/failed client is still waiting on parameters.
        private val authorizationState: TdApi.AuthorizationState = TdApi.AuthorizationStateWaitTdlibParameters(),
    ) : TdLibClientGateway {
        private val _updates = MutableSharedFlow<TdApi.Object>(replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        override val updates: SharedFlow<TdApi.Object> = _updates.asSharedFlow()

        @Volatile private var opened = false
        override val isOpen: Boolean get() = opened

        /** How many times the native client was created — the leak a duplicate start would cause. */
        @Volatile var openCount: Int = 0
            private set

        // When true, SetTdlibParameters is rejected (simulating bad credentials/parameters) until flipped.
        @Volatile var failParameters: Boolean = false
        // When true, SetTdlibParameters never answers — the gateway's timeout exception is raised.
        @Volatile var timeoutParameters: Boolean = false
        // When true, CheckAuthenticationCode is rejected (a wrong code) with a recoverable 400.
        @Volatile var failCode: Boolean = false
        // When true, the email-address / email-code requests are rejected with a recoverable 400.
        @Volatile var failEmailAddress: Boolean = false
        @Volatile var failEmailCode: Boolean = false
        @Volatile var requests: List<TdApi.Function<*>> = emptyList()
        /** What GetMe reports — the signed-in account's display identity. */
        @Volatile var me: TdApi.Object = TdApi.Ok()

        fun setParametersSent(): Int = requests.count { it is TdApi.SetTdlibParameters }

        /** Push a TDLib authorization update, as the native client would. */
        fun emit(state: TdApi.AuthorizationState) {
            _updates.tryEmit(TdApi.UpdateAuthorizationState(state))
        }

        override fun open() {
            opened = true
            openCount++
            _updates.tryEmit(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateWaitTdlibParameters()))
        }

        override suspend fun request(request: TdApi.Function<*>): TdApi.Object {
            requests += request
            return when (request) {
                is TdApi.GetAuthorizationState -> authorizationState
                is TdApi.SetTdlibParameters -> when {
                    timeoutParameters -> throw TdLibRequestTimeoutException(30_000L)
                    failParameters -> TdApi.Error(400, "invalid api_id")
                    else -> TdApi.Ok()
                }
                is TdApi.CheckAuthenticationCode -> if (failCode) TdApi.Error(400, "code invalid") else TdApi.Ok()
                is TdApi.SetAuthenticationEmailAddress ->
                    if (failEmailAddress) TdApi.Error(400, "email invalid") else TdApi.Ok()
                is TdApi.CheckAuthenticationEmailCode ->
                    if (failEmailCode) TdApi.Error(400, "code invalid") else TdApi.Ok()
                is TdApi.ResendLoginEmailAddressCode ->
                    TdApi.EmailAddressAuthenticationCodeInfo("a***@gmail.com", 5)
                is TdApi.GetMe -> me
                else -> TdApi.Ok()
            }
        }

        override fun close() { opened = false }
    }

    private class FakeKeyProvider : SecureTdLibKeyProvider {
        override suspend fun getOrCreate(): ByteArray = ByteArray(32) { (it + 1).toByte() }
    }

    private fun manager(gateway: TdLibClientGateway) = TelegramSessionManager(
        context = ApplicationProvider.getApplicationContext<Context>(),
        gateway = gateway,
        keyProvider = FakeKeyProvider(),
        apiId = 12345,
        apiHash = "hash",
        applicationVersion = "1.0.0",
    )

    @Test
    fun `initial authorization update during open triggers SetTdlibParameters and is never lost`() = runBlocking {
        val gateway = FakeGateway()
        val manager = manager(gateway)
        manager.initialize()

        withTimeout(3_000) {
            while (gateway.requests.none { it is TdApi.SetTdlibParameters }) delay(20)
        }
        assertTrue(gateway.requests.any { it is TdApi.SetTdlibParameters })
        manager.close()
    }

    @Test
    fun `a second start while one is in flight never opens a second client`() = runBlocking {
        // TDLib's Android binding offers no way to close a client, so a second `Client.create()` from a
        // racing first-open leaks a native client and its threads for the life of the process — with two
        // collectors both driving the auth state machine and both sending SetTdlibParameters. The gate
        // "Connect Telegram" is a button the user can tap twice.
        //
        // `initialize()` claims its single-flight flag on the caller's thread, before launching, so this
        // assertion is deterministic rather than a timing win.
        val gateway = FakeGateway()
        val m = manager(gateway)
        m.initialize()
        m.initialize()

        withTimeout(3_000) { while (gateway.requests.none { it is TdApi.SetTdlibParameters }) delay(20) }
        assertEquals(1, gateway.openCount)
        m.close()
    }

    @Test
    fun `a failed start can be retried and re-sends the parameters`() = runBlocking {
        val gateway = FakeGateway().apply { failParameters = true }
        val m = manager(gateway)
        m.initialize()

        // The failure surfaces as Failed, and the parameters were attempted at least once.
        withTimeout(3_000) { while (m.state.value !is TelegramAuthState.Failed) delay(20) }
        assertTrue(m.state.value is TelegramAuthState.Failed)
        val attemptsBeforeRetry = gateway.setParametersSent()
        assertTrue(attemptsBeforeRetry >= 1)

        // The credential is fixed: subsequent SetTdlibParameters succeed.
        gateway.failParameters = false

        // Retry — exactly what the onboarding "Try again" button triggers. It must re-drive, not no-op,
        // since TDLib won't re-emit the parameters wait on its own.
        m.initialize()
        withTimeout(3_000) { while (gateway.setParametersSent() <= attemptsBeforeRetry) delay(20) }
        assertTrue(
            "retry should re-send SetTdlibParameters",
            gateway.setParametersSent() > attemptsBeforeRetry,
        )

        // Once the resend succeeds, the flow is no longer Failed.
        withTimeout(3_000) { while (m.state.value is TelegramAuthState.Failed) delay(20) }
        assertTrue(m.state.value !is TelegramAuthState.Failed)
        m.close()
    }

    @Test
    fun `a timed-out request surfaces as Failed instead of parking on Initializing`() = runBlocking {
        // Before the gateway had a timeout, a request whose response never arrived suspended its caller
        // forever: the auth screen sat on Initializing with no error and no way forward. The timeout is
        // reported as a TDLib-shaped error (499, which classify maps to Network) so the failure becomes
        // visible instead of being dropped as control flow.
        val gateway = FakeGateway().apply { timeoutParameters = true }
        val m = manager(gateway)
        m.initialize()

        withTimeout(3_000) { while (m.state.value !is TelegramAuthState.Failed) delay(20) }
        val state = m.state.value
        assertTrue(state is TelegramAuthState.Failed)
        assertTrue(
            "a timeout should read to the user as a connection problem",
            (state as TelegramAuthState.Failed).error is TelegramError.Network,
        )
        m.close()
    }

    @Test
    fun `unconfigured credentials fail fast without opening the client`() = runBlocking {
        val gateway = FakeGateway()
        val m = TelegramSessionManager(
            context = ApplicationProvider.getApplicationContext<Context>(),
            gateway = gateway,
            keyProvider = FakeKeyProvider(),
            apiId = 0,
            apiHash = "",
            applicationVersion = "1.0.0",
        )
        m.initialize()
        // No TDLib client is opened; the honest NotConfigured failure is surfaced immediately.
        assertTrue(m.state.value is TelegramAuthState.Failed)
        assertFalse(gateway.isOpen)
    }

    @Test
    fun `a rejected code returns to the code field instead of a dead-end`() = runBlocking {
        val gateway = FakeGateway().apply { failCode = true }
        val m = manager(gateway)
        m.submitCode("00000")

        withTimeout(3_000) { while (m.state.value !is TelegramAuthState.WaitingForCode) delay(20) }
        val state = m.state.value as TelegramAuthState.WaitingForCode
        // Recoverable: still the code-waiting state (input stays visible) carrying an inline reason.
        assertNotNull(state.error)
        m.close()
    }

    // --- Email sign-in ------------------------------------------------------------------------

    @Test
    fun `a rejected email address returns to the email field instead of a dead-end`() = runBlocking {
        val gateway = FakeGateway().apply { failEmailAddress = true }
        val m = manager(gateway)
        m.submitEmailAddress("not-an-address")

        withTimeout(3_000) { while (m.state.value !is TelegramAuthState.WaitingForEmailAddress) delay(20) }
        val state = m.state.value as TelegramAuthState.WaitingForEmailAddress
        // Still the email-waiting state, with an inline reason — the input never disappears.
        assertNotNull(state.error)
        // And the address actually reached TDLib rather than being swallowed locally.
        assertTrue(gateway.requests.any { it is TdApi.SetAuthenticationEmailAddress })
        m.close()
    }

    @Test
    fun `an empty email address is never sent to TDLib`() = runBlocking {
        val gateway = FakeGateway()
        val m = manager(gateway)
        m.submitEmailAddress("   ")
        delay(200)
        assertTrue(gateway.requests.none { it is TdApi.SetAuthenticationEmailAddress })
        m.close()
    }

    @Test
    fun `a rejected email code keeps the masked address and stays on the field`() = runBlocking {
        val gateway = FakeGateway().apply { failEmailCode = true }
        val m = manager(gateway)
        m.initialize()
        // open() emits the parameters wait, and the update flow has replay = 1 with DROP_OLDEST, so a
        // state pushed before that exchange finishes is dropped rather than replayed. Wait for the
        // parameters request to be issued first — sendParameters() publishes nothing on success, so
        // once it has been sent nothing else will overwrite what we push next.
        withTimeout(3_000) { while (gateway.setParametersSent() < 1) delay(20) }
        // Drive TDLib into the email-code wait, as it would after accepting an address.
        gateway.emit(
            TdApi.AuthorizationStateWaitEmailCode().apply {
                codeInfo = TdApi.EmailAddressAuthenticationCodeInfo("a***@gmail.com", 5)
            },
        )
        withTimeout(3_000) { while (m.state.value !is TelegramAuthState.WaitingForEmailCode) delay(20) }

        m.submitEmailCode("00000")
        withTimeout(3_000) {
            while ((m.state.value as? TelegramAuthState.WaitingForEmailCode)?.error == null) delay(20)
        }
        val state = m.state.value as TelegramAuthState.WaitingForEmailCode
        assertEquals("a***@gmail.com", state.emailPattern)
        assertEquals(5, state.codeLength)
        assertNotNull(state.error)
        m.close()
    }

    @Test
    fun `resending an email code refreshes the masked address and clears the error`() = runBlocking {
        val gateway = FakeGateway()
        val m = manager(gateway)
        m.initialize()
        // open() emits the parameters wait, and the update flow has replay = 1 with DROP_OLDEST, so a
        // state pushed before that exchange finishes is dropped rather than replayed. Wait for the
        // parameters request to be issued first — sendParameters() publishes nothing on success, so
        // once it has been sent nothing else will overwrite what we push next.
        withTimeout(3_000) { while (gateway.setParametersSent() < 1) delay(20) }
        gateway.emit(
            TdApi.AuthorizationStateWaitEmailCode().apply {
                codeInfo = TdApi.EmailAddressAuthenticationCodeInfo("old***@gmail.com", 4)
            },
        )
        withTimeout(3_000) { while (m.state.value !is TelegramAuthState.WaitingForEmailCode) delay(20) }

        m.resendEmailCode()
        withTimeout(3_000) {
            while ((m.state.value as? TelegramAuthState.WaitingForEmailCode)?.emailPattern != "a***@gmail.com") delay(20)
        }
        val state = m.state.value as TelegramAuthState.WaitingForEmailCode
        assertEquals(5, state.codeLength)
        assertEquals(null, state.error)
        assertTrue(gateway.requests.any { it is TdApi.ResendLoginEmailAddressCode })
        m.close()
    }

    @Test
    fun `a stale email-code reply never pulls the session back to the input screen`() = runBlocking {
        // Two taps on Submit, or a reply that lands after TDLib already moved on. Re-publishing the
        // email-code wait here would put an input back on screen for a code the server has already
        // accepted — and every further attempt would then be rejected as "no email code pending",
        // which is exactly the dead end this flow exists to remove.
        val gateway = FakeGateway().apply { failEmailCode = true }
        val m = manager(gateway)
        m.initialize()
        // See the note above: wait for the parameters request before pushing state, or replay = 1 with
        // DROP_OLDEST discards it when open()'s own parameters wait lands on the same flow.
        withTimeout(3_000) { while (gateway.setParametersSent() < 1) delay(20) }
        gateway.emit(
            TdApi.AuthorizationStateWaitEmailCode().apply {
                codeInfo = TdApi.EmailAddressAuthenticationCodeInfo("a***@gmail.com", 5)
            },
        )
        withTimeout(3_000) { while (m.state.value !is TelegramAuthState.WaitingForEmailCode) delay(20) }

        // TDLib accepts the code and leaves the wait.
        gateway.emit(TdApi.AuthorizationStateReady())
        withTimeout(3_000) { while (m.state.value is TelegramAuthState.WaitingForEmailCode) delay(20) }

        m.submitEmailCode("00000")
        delay(200)

        assertTrue(
            "a late reply must not restore the email-code input",
            m.state.value !is TelegramAuthState.WaitingForEmailCode,
        )
        m.close()
    }

    // --- Account identity ---------------------------------------------------------------------

    /** Drives a fresh manager to Authorized with [account] as the GetMe reply. */
    private suspend fun authorizedUserFor(account: TdApi.Object): TelegramUser {
        val gateway = FakeGateway().apply { me = account }
        val m = manager(gateway)
        m.initialize()
        // Wait for the parameters request before pushing state: replay = 1 with DROP_OLDEST discards
        // an update that lands while open()'s own parameters wait is on the same flow.
        withTimeout(3_000) { while (gateway.setParametersSent() < 1) delay(20) }
        gateway.emit(TdApi.AuthorizationStateReady())
        withTimeout(3_000) { while (m.state.value !is TelegramAuthState.Authorized) delay(20) }
        val user = (m.state.value as TelegramAuthState.Authorized).user
        m.close()
        return user
    }

    @Test
    fun `an active username is carried through from TDLib`() = runBlocking {
        // TDLib reports usernames as a struct, not a single field; the active username wins.
        val user = authorizedUserFor(
            TdApi.User().apply {
                id = 99L
                firstName = "Ada"
                lastName = "Lovelace"
                usernames = TdApi.Usernames(arrayOf("ada_l"), emptyArray<String>(), "ada", emptyArray<String>())
            },
        )
        assertEquals("ada_l", user.username)
    }

    @Test
    fun `the editable username is the fallback when no active username exists`() = runBlocking {
        val user = authorizedUserFor(
            TdApi.User().apply {
                id = 99L
                firstName = "Ada"
                lastName = null
                usernames = TdApi.Usernames(emptyArray<String>(), emptyArray<String>(), "ada", emptyArray<String>())
            },
        )
        assertEquals("ada", user.username)
    }
}
