package com.telepic.telegram

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
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
 * A TDLib request produced no response within [TdLibClientGatewayImpl]'s timeout.
 *
 * Extends [RuntimeException], **not** `CancellationException`, deliberately. Every layer above treats
 * `CancellationException` as control flow and rethrows it untouched (`TelegramSessionManager.safeRequest`,
 * `DefaultBackupRepository`, the ViewModels), so a timeout modelled as a cancellation would be silently
 * dropped rather than reported. As an ordinary failure it reaches each caller's normal error path:
 * `safeRequest` turns it into a `TdApi.Error`, and the backup engine records a transient, retryable
 * failure.
 */
class TdLibRequestTimeoutException(timeoutMillis: Long) :
    RuntimeException("Telegram did not respond within ${timeoutMillis / 1000} seconds.")

/**
 * Production [TdLibClientGateway] using the official Java TDLib bindings
 * (`org.drinkless.tdlib.Client` / `TdApi`). No JSON or hand-written JNI layer.
 */
class TdLibClientGatewayImpl(
    /**
     * How long a single request may take before it is abandoned.
     *
     * TDLib's `send()` is fire-and-forget with a callback, so a request whose response never arrives
     * (a dropped connection, a native thread that never wakes) used to suspend its caller **forever**:
     * the auth screen and the cloud tab would both hang with no error and no way out. TDLib returns
     * promptly for long-running operations — downloads and sends report progress through updates — so
     * this bounds the *response*, not the operation.
     */
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) : TdLibClientGateway {

    // replay = 1 keeps the most recent update for a subscriber that attaches microseconds late
    // (TDLib can emit the first UpdateAuthorizationState during/right after open()), so the initial
    // authorization event is never lost and a fresh first login cannot hang.
    private val _updates = MutableSharedFlow<TdApi.Object>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val updates: SharedFlow<TdApi.Object> = _updates.asSharedFlow()

    @Volatile
    private var client: Client? = null

    /**
     * Whether [close] has run. Guards [open] against re-creating a client that can never be released.
     *
     * [close] can only drop the reference, because the binding exposes no instance `close()` — so the
     * native client and its threads live until the process ends. Without this flag, `open()` after
     * `close()` passed its `client != null` check (the reference had just been nulled) and called
     * [Client.create] a second time, leaking a second native client for the process lifetime. The
     * gateway is a single-use resource; saying so is cheaper than leaking.
     */
    @Volatile
    private var closed = false

    override val isOpen: Boolean get() = client != null

    /**
     * Creating the client is expensive, uncloseable, and must happen exactly once per process: the
     * binding exposes no instance `close()`, so a second [Client.create] from a racing first-open leaks
     * a native client and its threads for the life of the app. `@Synchronized` is the cheap guarantee —
     * the caller in `TelegramSessionManager` is already single-flight, and this is the second lock.
     *
     * @throws IllegalStateException if the gateway was already closed; it cannot be reopened.
     */
    @Synchronized
    override fun open() {
        if (client != null) return
        check(!closed) { "The TDLib client was closed and cannot be reopened" }
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
        return try {
            withTimeout(requestTimeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    active.send(request) { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            // `withTimeout` signals with a CancellationException subclass, which every caller rethrows
            // as control flow — so it is translated here into an ordinary failure that can actually be
            // reported. A response that arrives after the timeout finds an inactive continuation and
            // is ignored.
            throw TdLibRequestTimeoutException(requestTimeoutMillis)
        }
    }

    override fun close() {
        // The bundled org.drinkless.tdlib.Client exposes no instance close(); the native client's
        // lifecycle is tied to the process/session. Drop the reference so no new requests are sent,
        // and latch the gateway shut so [open] cannot create a second, equally unreleasable client.
        closed = true
        client = null
    }

    private companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 30_000L
    }
}
