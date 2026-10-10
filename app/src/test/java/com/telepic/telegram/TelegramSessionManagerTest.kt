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

        // When true, SetTdlibParameters is rejected (simulating bad credentials/parameters) until flipped.
        @Volatile var failParameters: Boolean = false
        // When true, CheckAuthenticationCode is rejected (a wrong code) with a recoverable 400.
        @Volatile var failCode: Boolean = false
        @Volatile var requests: List<TdApi.Function<*>> = emptyList()

        fun setParametersSent(): Int = requests.count { it is TdApi.SetTdlibParameters }

        override fun open() {
            opened = true
            _updates.tryEmit(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateWaitTdlibParameters()))
        }

        override suspend fun request(request: TdApi.Function<*>): TdApi.Object {
            requests += request
            return when (request) {
                is TdApi.GetAuthorizationState -> authorizationState
                is TdApi.SetTdlibParameters -> if (failParameters) TdApi.Error(400, "invalid api_id") else TdApi.Ok()
                is TdApi.CheckAuthenticationCode -> if (failCode) TdApi.Error(400, "code invalid") else TdApi.Ok()
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
}
