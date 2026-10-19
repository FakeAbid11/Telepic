package com.telepic.telegram

import com.telepic.diagnostics.TelepicLog
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

    /**
     * As [request], but with an explicit response timeout, for operations the caller knows run long.
     *
     * A **synchronous** TDLib download blocks until the file is fully transferred, so it is the one
     * request whose response is legitimately allowed to outlive the default timeout. The default body
     * delegates to [request], so a fake that implements only the ordinary form is unaffected.
     */
    suspend fun request(request: TdApi.Function<*>, timeoutMillis: Long): TdApi.Object =
        request(request)

    /** Close the client (idempotent). */
    fun close()

    /**
     * Drop the current client so the next [open] creates a **fresh** one.
     *
     * TDLib closes its client when the session reaches `AuthorizationStateClosed` — which is exactly
     * what a sign-out produces — and the Java binding unregisters the update handler for that client
     * id at the same moment. The session is therefore undriveable afterwards: no further update ever
     * arrives, and the native client cannot be released from Java. The only way forward is a new
     * client, and that is what this enables.
     *
     * Deliberately **not** [close]: this is a recovery, not a terminal shutdown. The default body is a
     * no-op so a fake gateway does not have to implement it.
     */
    fun reset() {}
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
        } catch (link: UnsatisfiedLinkError) {
            // Not fatal here: Client.create may still succeed if its static block already loaded the
            // library. Recorded (debug — this is an expected outcome on the fallback path, not a
            // failure) so a genuinely missing native library is diagnosable instead of silent.
            TelepicLog.debug("TdLibGateway", "explicit tdjni load failed; relying on Client's static initializer", link)
        }
        client = Client.create(
            Client.ResultHandler { update -> _updates.tryEmit(update) }, // update handler
            // TDLib reports a per-update failure and a backend-internal exception through these
            // callbacks; they must never crash the app, but swallowing them silently left a device-only
            // TDLib failure with no trace. Debug-gated and message-only — no request/response payload,
            // which can carry private media or credentials, is ever logged.
            Client.ExceptionHandler { exception ->
                TelepicLog.debug("TdLibGateway", "TDLib update-processing exception", exception)
            },
            Client.ExceptionHandler { exception ->
                // The *default* exception handler is the fallback for a request that was sent without
                // its own handler. It is debug-gated on purpose — it is a rare, per-request failure and
                // a release log full of stack traces helps nobody — but the request path below now
                // always installs its own handler, so this only ever sees a backend-internal fault.
                TelepicLog.debug("TdLibGateway", "TDLib default exception", exception)
            },
        )
        // Route TDLib's own logger through the app's logging seam instead of letting the native
        // default write straight to logcat. Verbosity 0 is "fatal errors only", so this is quiet in
        // normal operation and still leaves a trace when TDLib itself fails. The message is truncated
        // because TDLib log lines can be long, and it is debug-gated for the same reason the rest of
        // the seam is.
        Client.setLogMessageHandler(
            TDLIB_LOG_VERBOSITY,
            Client.LogMessageHandler { _, message ->
                TelepicLog.debug("TdLibGateway", "TDLib: ${message.take(MAX_LOG_MESSAGE_CHARS)}")
            },
        )
    }

    /**
     * Discard the closed client so a fresh one can be created.
     *
     * TDLib has already torn the client down by the time this is called (it reaches
     * `AuthorizationStateClosed` on its own), and the binding has unregistered its update handler —
     * so the reference is the only thing left holding a dead client. Dropping it lets the next
     * [open] create a new one, which is what makes signing in again possible after a sign-out.
     *
     * [closed] is deliberately *not* set: [close] is the terminal shutdown, this is a recovery.
     */
    @Synchronized
    override fun reset() {
        client = null
    }

    override suspend fun request(request: TdApi.Function<*>): TdApi.Object =
        request(request, requestTimeoutMillis)

    override suspend fun request(request: TdApi.Function<*>, timeoutMillis: Long): TdApi.Object {
        val active = client ?: throw IllegalStateException("TDLib client is not open")
        return try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    // The three-argument overload is the one that matters here. With the two-argument
                    // form the binding registers no per-request exception handler, so a request-level
                    // failure falls back to the *default* handler — which is debug-gated, i.e. silent
                    // in a release build. The caller then waited out the full timeout and was told
                    // "Telegram did not respond" with no reason at all. With an explicit handler the
                    // real cause reaches the caller immediately.
                    active.send(
                        request,
                        Client.ResultHandler { result ->
                            if (continuation.isActive) continuation.resume(result)
                        },
                        Client.ExceptionHandler { throwable ->
                            if (continuation.isActive) continuation.resumeWithException(throwable)
                        },
                    )
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            // `withTimeout` signals with a CancellationException subclass, which every caller rethrows
            // as control flow — so it is translated here into an ordinary failure that can actually be
            // reported. A response that arrives after the timeout finds an inactive continuation and
            // is ignored.
            throw TdLibRequestTimeoutException(timeoutMillis)
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

        /** TDLib's own log level: 0 is "fatal errors only". */
        const val TDLIB_LOG_VERBOSITY = 0

        /** A TDLib log line is truncated to this many characters before it reaches the log seam. */
        const val MAX_LOG_MESSAGE_CHARS = 200
    }
}
