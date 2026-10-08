package com.telepix.telegram

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/**
 * A thin, coroutine-friendly seam over the Java TDLib [Client]. Everything below
 * [TelegramSessionManager] talks to TDLib only through this interface, so the auth flow can be
 * unit-tested with a fake gateway (no Telegram, no native library).
 */
interface TdLibClientGateway {
    /** Every TDLib update, as they arrive (on TDLib's own thread). */
    val updates: SharedFlow<TdApi.Object>

    /** Whether the native client is open. */
    val isOpen: Boolean

    /** Create the client. Throws if the native library cannot be loaded. */
    fun open()

    /** Send a request and suspend for exactly one response object (Ok or [TdApi.Error]). */
    suspend fun request(request: TdApi.Function<*>): TdApi.Object

    /** Close the client (idempotent). */
    fun close()
}

/**
 * Production [TdLibClientGateway] using the official Java TDLib bindings
 * (`org.drinkless.tdlib.Client` / `TdApi`). No JSON or hand-written JNI layer.
 */
class TdLibClientGatewayImpl : TdLibClientGateway {

    private val _updates = MutableSharedFlow<TdApi.Object>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val updates: SharedFlow<TdApi.Object> = _updates.asSharedFlow()

    @Volatile
    private var client: Client? = null

    override val isOpen: Boolean get() = client != null

    override fun open() {
        if (client != null) return
        // Client's own static initializer loads "tdjni"; we also attempt it explicitly so a
        // missing native library fails here (surfaced as an initialization error) instead of
        // crashing later. Any Throwable from native binding is propagated to the caller.
        try {
            System.loadLibrary("tdjni")
        } catch (_: UnsatisfiedLinkError) {
            // Ignore: Client.create may still succeed if its static block already loaded it.
        }
        client = Client.create(
            { update -> _updates.tryEmit(update) }, // update handler
            { _ -> /* update processing exception: swallow, never crash */ },
            { _ -> /* default exception handler: swallow */ },
        )
    }

    override suspend fun request(request: TdApi.Function<*>): TdApi.Object {
        val active = client ?: throw IllegalStateException("TDLib client is not open")
        return suspendCancellableCoroutine { continuation ->
            active.send(request) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }

    override fun close() {
        val active = client ?: return
        client = null
        try {
            active.close()
        } catch (_: Throwable) {
            // Never let a native close failure propagate to the UI.
        }
    }
}
