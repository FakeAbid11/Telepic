package com.telepix.telegram

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the authorization-startup race: TDLib can emit the first `UpdateAuthorizationState`
 * during/right after `open()`. The session manager must still receive it and send the TDLib
 * parameters — otherwise a fresh first login hangs. Uses a fake gateway that emits the initial
 * update at open() time (before the collector is guaranteed to have subscribed) plus the gateway's
 * replay guard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TelegramSessionManagerTest {

    private class FakeGateway : TdLibClientGateway {
        // Same replay configuration as the real gateway: the initial update must survive a late subscriber.
        private val _updates = MutableSharedFlow<TdApi.Object>(replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        override val updates: SharedFlow<TdApi.Object> = _updates.asSharedFlow()
        override val isOpen: Boolean get() = true

        @Volatile var requests: List<TdApi.Function<*>> = emptyList()

        override fun open() {
            // The initial authorization state arrives as soon as the client opens.
            _updates.tryEmit(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateWaitTdlibParameters()))
        }

        override suspend fun request(request: TdApi.Function<*>): TdApi.Object {
            requests += request
            return TdApi.Ok()
        }

        override fun close() = Unit
    }

    private class FakeKeyProvider : SecureTdLibKeyProvider {
        override suspend fun getOrCreate(): ByteArray = ByteArray(32) { (it + 1).toByte() }
    }

    @Test
    fun `initial authorization update during open triggers SetTdlibParameters and is never lost`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val gateway = FakeGateway()
        val manager = TelegramSessionManager(
            context = context,
            gateway = gateway,
            keyProvider = FakeKeyProvider(),
            apiId = 12345,
            apiHash = "hash",
            applicationVersion = "1.0.0",
        )
        manager.initialize()

        // The manager must react to the startup authorization state by sending the TDLib parameters.
        withTimeout(3_000) {
            while (gateway.requests.none { it is TdApi.SetTdlibParameters }) delay(20)
        }
        assertTrue(gateway.requests.any { it is TdApi.SetTdlibParameters })
        manager.close()
    }
}
