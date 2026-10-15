package com.telepic.telegram

import android.content.Context
import android.os.Build
import com.telepic.diagnostics.TelepicLog
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Owns the single TDLib client for the app and drives the authentication state machine.
 *
 * It initializes TDLib lazily (opening the client + collecting updates) and maps every TDLib
 * authorization state to [TelegramAuthState]. It never fabricates authorization: [
 * TelegramAuthState.Authorized] is emitted only after TDLib reports [TdApi.AuthorizationStateReady]
 * and the account is read via GetMe. All work happens off the main thread; failures degrade to
 * [TelegramAuthState.Failed] instead of crashing the app.
 */
class TelegramSessionManager(
    context: Context,
    private val gateway: TdLibClientGateway,
    private val keyProvider: SecureTdLibKeyProvider,
    private val apiId: Int,
    private val apiHash: String,
    private val applicationVersion: String,
) {
    private val appContext = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<TelegramAuthState>(TelegramAuthState.NotConnected)
    val state: StateFlow<TelegramAuthState> = _state.asStateFlow()

    private val authorizedUser = MutableStateFlow<TelegramUser?>(null)
    private var lastAuthorizationState: TdApi.AuthorizationState? = null

    @Volatile
    private var collectorJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var parametersSent = false

    /**
     * Single-flight guard for [initialize]. The work it kicks off runs *inside* a coroutine, so
     * `@Synchronized` alone never covered the `collectorJob == null` check or `gateway.open()` — two
     * taps on "Connect Telegram" could both observe a fresh collector and each create a native TDLib
     * client, of which the binding can never close the first. Written from the caller's thread, read
     * from the app's; atomic state is the only correct sharing here.
     */
    private val starting = AtomicBoolean(false)

    /**
     * Whether [close] has run. Terminal, and checked before anything else in [initialize].
     *
     * [scope] is a constructor `val`, so [close]'s `scope.cancel()` cannot be undone. Previously that
     * made [close] a trap: a later [initialize] set the state to `Initializing`, won the [starting]
     * compare-and-set, and then launched into a cancelled scope — so the coroutine never ran, the
     * `finally` that clears [starting] never executed, and the UI sat on `Initializing` forever with
     * no error and no timeout. Refusing up front and reporting it is the honest alternative.
     */
    @Volatile
    private var closed = false

    // A build compiled without real credentials (CI defaults apiId=0) can never log in; surface that
    // honestly up front instead of opening TDLib and reporting a generic engine error.
    private val credentialsConfigured = apiId > 0 && apiHash.isNotBlank()

    /**
     * Begin (or re-attempt) authentication. Idempotent and safe to call again after a
     * [TelegramAuthState.Failed]: the update collector is started at most once, the client is opened
     * only if not already open, and the flow is re-driven from TDLib's *current* authorization state —
     * so a login whose `SetTdlibParameters` was rejected can recover without an app restart (TDLib does
     * not re-announce that wait on its own).
     *
     * A call that arrives while one is already in flight is dropped rather than queued: see [starting].
     */
    @Synchronized
    fun initialize() {
        if (closed) {
            // See [closed]: the scope is cancelled for good, so there is no start to perform. Saying so
            // is strictly better than the silent hang this used to produce.
            _state.value = TelegramAuthState.Failed(
                TelegramError.Initialization("The Telegram session was closed."),
            )
            return
        }
        if (!credentialsConfigured) {
            _state.value = TelegramAuthState.Failed(TelegramError.NotConfigured())
            return
        }
        if (!starting.compareAndSet(false, true)) {
            TelepicLog.debug("TelegramAuth", "a start is already in flight; ignoring the duplicate request")
            return
        }
        _state.value = TelegramAuthState.Initializing
        scope.launch {
            try {
                startOnce()
            } finally {
                starting.set(false)
            }
        }
    }

    private suspend fun startOnce() {
        val isRetry = collectorJob != null
        if (collectorJob == null) {
            // Subscribe to updates BEFORE opening the client, so the initial authorization update
            // (emitted during/right after open()) is received, not lost. The gateway also replays the
            // latest update as a second guard against the open/subscribe ordering race.
            collectorJob = scope.launch { gateway.updates.collect { update -> handle(update) } }
            try {
                gateway.open()
            } catch (throwable: Throwable) {
                TelepicLog.error("TelegramAuth", "TDLib could not be started", throwable)
                collectorJob?.cancel()
                collectorJob = null
                _state.value = TelegramAuthState.Failed(
                    TelegramError.Initialization("Telegram engine could not start."),
                )
                return
            }
            // Silence TDLib's own logger *before* the first request that carries a credential.
            // TDLib logs at a default verbosity, and SetTdlibParameters is handed the api_hash
            // (sendParameters, below); Telepic's own logging never carries a credential (see
            // TelepicLog), so TDLib's logger is the only path one could reach a log through.
            // Level 0 = fatal errors only. Best-effort: a rejected log request must not fail the login.
            safeRequest(TdApi.SetLogVerbosityLevel(0))
            // First start: opening makes TDLib emit the initial authorization state, which the
            // collector handles. No need (and no race) from also querying it here.
        } else if (isRetry) {
            // Retry after a failure: the client is already open and TDLib will NOT re-announce the
            // current wait (e.g. after a rejected SetTdlibParameters), so re-drive from its state.
            driveFromCurrentState()
        }
    }

    /** Re-drive the auth flow from whatever state TDLib is currently in (used on first start and on retry). */
    private suspend fun driveFromCurrentState() {
        val current = safeRequest(TdApi.GetAuthorizationState())
        if (current is TdApi.AuthorizationState) {
            // A prior SetTdlibParameters may have errored and TDLib won't re-emit the wait, so allow the
            // parameters to be re-sent for this attempt instead of latching on the earlier send.
            if (current is TdApi.AuthorizationStateWaitTdlibParameters) parametersSent = false
            onAuthorizationState(current)
        }
    }

    private suspend fun handle(update: TdApi.Object) {
        if (update is TdApi.UpdateAuthorizationState) {
            onAuthorizationState(update.authorizationState)
        }
    }

    private suspend fun onAuthorizationState(state: TdApi.AuthorizationState) {
        lastAuthorizationState = state
        // Publish the state we are now in BEFORE acting on it. If acting fails (e.g. a rejected
        // SetTdlibParameters), fail() writes Failed as the terminal update; publishing first means that
        // failure is not overwritten back to Initializing by a later map of the same wait state.
        publishMappedState()
        if (state is TdApi.AuthorizationStateWaitTdlibParameters) {
            sendParameters()
        }
        if (state is TdApi.AuthorizationStateReady && authorizedUser.value == null) {
            fetchAccount()
        }
    }

    private suspend fun sendParameters() {
        // Single choke-point guard: never send twice for the same attempt, even if the update stream and
        // the re-drive both observe the parameters wait. A retry clears the flag in driveFromCurrentState.
        if (parametersSent) return
        parametersSent = true
        val dir = File(appContext.noBackupFilesDir, "tdlib").apply { mkdirs() }
        val databaseKey = keyProvider.getOrCreate()
        val request = TdApi.SetTdlibParameters(
            /* useTestDc = */ false,
            /* databaseDirectory = */ dir.absolutePath,
            /* filesDirectory = */ "${dir.absolutePath}/files",
            /* databaseEncryptionKey = */ databaseKey,
            /* useFileDatabase = */ true,
            /* useChatInfoDatabase = */ true,
            /* useMessageDatabase = */ true,
            /* useSecretChats = */ false,
            /* apiId = */ apiId,
            /* apiHash = */ apiHash,
            /* systemLanguageCode = */ Locale.getDefault().language,
            /* deviceModel = */ Build.MODEL,
            /* systemVersion = */ Build.VERSION.RELEASE,
            /* applicationVersion = */ applicationVersion,
        )
        when (val response = safeRequest(request)) {
            is TdApi.Error -> fail(TelegramError.classify(response.code, response.message))
            else -> Unit
        }
    }

    private suspend fun fetchAccount() {
        when (val me = safeRequest(TdApi.GetMe())) {
            is TdApi.User -> {
                authorizedUser.value = TelegramUser(
                    id = me.id,
                    firstName = me.firstName,
                    lastName = me.lastName,
                    // TDLib exposes usernames as a struct (active + editable). Prefer an active
                    // username, then fall back to the account's own editable one; both are optional.
                    username = me.usernames?.activeUsernames?.firstOrNull()
                        ?: me.usernames?.editableUsername?.takeIf { it.isNotBlank() },
                )
                publishMappedState()
            }
            is TdApi.Error -> fail(TelegramError.classify(me.code, me.message))
            else -> Unit
        }
    }

    fun submitPhoneNumber(phoneNumber: String) {
        val normalized = phoneNumber.trim()
        if (normalized.isEmpty()) return
        scope.launch {
            val settings = TdApi.PhoneNumberAuthenticationSettings().apply {
                allowFlashCall = false
                isCurrentPhoneNumber = false
            }
            when (val response = safeRequest(TdApi.SetAuthenticationPhoneNumber(normalized, settings))) {
                // A rejected number is recoverable: TDLib stays at the phone wait and does not re-emit,
                // so keep the input visible and surface the reason inline instead of a dead-end Failed.
                is TdApi.Error -> _state.value = TelegramAuthState.WaitingForPhoneNumber(
                    error = TelegramError.classify(response.code, response.message, TelegramError.AuthStage.PhoneNumber).message,
                )
                else -> Unit
            }
        }
    }

    fun submitCode(code: String) {
        scope.launch {
            when (val response = safeRequest(TdApi.CheckAuthenticationCode(code.trim()))) {
                is TdApi.Error -> _state.value = TelegramAuthState.WaitingForCode(
                    error = TelegramError.classify(response.code, response.message, TelegramError.AuthStage.Code).message,
                )
                else -> Unit
            }
        }
    }

    fun submitPassword(password: String) {
        scope.launch {
            // The password is passed straight to TDLib and never stored or logged.
            when (val response = safeRequest(TdApi.CheckAuthenticationPassword(password))) {
                is TdApi.Error -> _state.value = TelegramAuthState.WaitingForPassword(
                    error = TelegramError.classify(response.code, response.message, TelegramError.AuthStage.Password).message,
                )
                else -> Unit
            }
        }
    }

    /**
     * Submit the email address TDLib asked for (`AuthorizationStateWaitEmailAddress`).
     *
     * A rejected address is recoverable: TDLib stays at the email wait and does not re-emit, so this
     * keeps the input on screen and surfaces the reason inline — the same shape [submitPhoneNumber]
     * uses, and the reason an email-only account is no longer stranded on a spinner.
     */
    fun submitEmailAddress(emailAddress: String) {
        val normalized = emailAddress.trim()
        if (normalized.isEmpty()) return
        scope.launch {
            when (val response = safeRequest(TdApi.SetAuthenticationEmailAddress(normalized))) {
                is TdApi.Error -> _state.value = TelegramAuthState.WaitingForEmailAddress(
                    error = TelegramError.classify(
                        response.code,
                        response.message,
                        TelegramError.AuthStage.EmailAddress,
                    ).message,
                )
                else -> Unit
            }
        }
    }

    /** Submit the code Telegram emailed (`AuthorizationStateWaitEmailCode`). */
    fun submitEmailCode(code: String) {
        val normalized = code.trim()
        if (normalized.isEmpty()) return
        scope.launch {
            val request = TdApi.CheckAuthenticationEmailCode(
                TdApi.EmailAddressAuthenticationCode(normalized),
            )
            when (val response = safeRequest(request)) {
                is TdApi.Error -> publishEmailCode(
                    error = TelegramError.classify(
                        response.code,
                        response.message,
                        TelegramError.AuthStage.EmailCode,
                    ).message,
                )
                else -> Unit
            }
        }
    }

    /**
     * Ask Telegram to email a fresh code. Without this, a code that expired or never arrived left the
     * user with no way forward except signing out entirely.
     */
    fun resendEmailCode() {
        scope.launch {
            when (val response = safeRequest(TdApi.ResendLoginEmailAddressCode())) {
                is TdApi.Error -> publishEmailCode(
                    error = TelegramError.classify(response.code, response.message).message,
                )
                is TdApi.EmailAddressAuthenticationCodeInfo -> publishEmailCode(error = null, info = response)
                else -> Unit
            }
        }
    }

    /**
     * Publish the email-code wait, carrying [error] (or refreshed [info] after a resend).
     *
     * The masked destination and expected length are read back from the published state rather than
     * cached in a field: `_state` is a `StateFlow`, so this is both memory-safe across coroutines and
     * free of a second copy of the same facts that could drift. [info] supersedes them when a resend
     * returned fresh values.
     *
     * Nothing is published unless the session is **still** waiting for an email code. TDLib moves on
     * the moment the code is accepted, so a slow or duplicated response — a second tap on Submit, a
     * resend that lands after sign-in already completed — would otherwise drag a signed-in session
     * back to an input screen, and every further attempt would be rejected as "no email code
     * pending". That is precisely the dead end this flow exists to remove, so a stale reply is
     * dropped rather than rendered.
     */
    private fun publishEmailCode(
        error: String?,
        info: TdApi.EmailAddressAuthenticationCodeInfo? = null,
    ) {
        val current = _state.value as? TelegramAuthState.WaitingForEmailCode ?: return
        _state.value = TelegramAuthState.WaitingForEmailCode(
            emailPattern = info?.emailAddressPattern?.takeIf { it.isNotBlank() } ?: current.emailPattern,
            codeLength = info?.length ?: current.codeLength,
            error = error,
        )
    }

    fun logout() {
        scope.launch {
            safeRequest(TdApi.LogOut())
        }
    }

    /**
     * Sends a request and reports a *failure* as a `TdApi.Error` — but never a cancellation.
     *
     * The blanket `catch (Throwable)` used to swallow [CancellationException] as well, which silently
     * downgraded a cancelled scope to "an empty response arrived": [sendParameters] then matched its
     * `else -> Unit` branch, published nothing, and left the UI parked on
     * [TelegramAuthState.Initializing] with neither an error nor a timeout to explain it. Cancellation
     * is control flow, not a Telegram outcome, and has to propagate.
     *
     * A [TdLibRequestTimeoutException] is the opposite case: a real outcome that must *not* propagate as
     * control flow. It is converted into a [TdApi.Error] so each caller's existing `is TdApi.Error`
     * branch fires and the failure becomes visible, instead of the silent no-op a `null` would produce.
     *
     * A leftover, unclassified `Throwable` is folded into the **same** shape for the same reason: a
     * bare `null` made each caller's `else -> Unit` branch swallow it, so an unexpected failure (a
     * native crash inside `send`, say) again left the UI parked on [TelegramAuthState.Initializing]
     * with no way forward. As an error it reaches the caller's normal failure path. [GENERIC_ERROR_CODE]
     * maps through [TelegramError.classify] to [TelegramError.Unknown], which is the honest reading of
     * "something we did not anticipate went wrong".
     */
    private suspend fun safeRequest(request: TdApi.Function<*>): TdApi.Object? =
        try {
            gateway.request(request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (timeout: TdLibRequestTimeoutException) {
            // The request's class name only: a request can carry a phone number or a code.
            TelepicLog.warn("TelegramAuth", "${request.javaClass.simpleName} timed out", timeout)
            TdApi.Error(TIMEOUT_ERROR_CODE, timeout.message ?: "Telegram did not respond in time.")
        } catch (throwable: Throwable) {
            TelepicLog.warn("TelegramAuth", "${request.javaClass.simpleName} failed", throwable)
            TdApi.Error(
                GENERIC_ERROR_CODE,
                throwable.message ?: "Telegram did not complete the request.",
            )
        }

    private fun fail(error: TelegramError) {
        _state.value = TelegramAuthState.Failed(error)
    }

    private fun publishMappedState() {
        _state.value = AuthorizationStateMapper.map(lastAuthorizationState, authorizedUser.value)
    }

    /**
     * Shut the session down for good. Terminal: [scope] is cancelled and cannot be recreated, so
     * [initialize] afterwards reports a failure rather than hanging (see [closed]).
     *
     * The published state is cleared as well. Leaving it at `Authorized` would keep telling every
     * caller — the backup engine's auth gate included — that a usable session exists after the client
     * behind it has gone.
     */
    fun close() {
        closed = true
        gateway.close()
        scope.cancel()
        collectorJob = null
        parametersSent = false
        lastAuthorizationState = null
        authorizedUser.value = null
        _state.value = TelegramAuthState.NotConnected
        // Nothing is in flight any more, so the latch must not stay set — otherwise a later
        // initialize() would be dropped as a "duplicate request" instead of reporting the closure.
        starting.set(false)
    }

    private companion object {
        /**
         * Reported in place of a real TDLib code when a request times out. 499 is TDLib's own
         * "connection closed" code and is already mapped to [TelegramError.Network] by
         * [TelegramError.classify], so a timeout reads to the user as a connection problem rather
         * than an unexplained failure.
         */
        const val TIMEOUT_ERROR_CODE = 499

        /**
         * Reported in place of a real TDLib code for an unclassified [Throwable] out of a request.
         * Deliberately not one of the codes [TelegramError.classify] special-cases (400/401/420/499),
         * so it surfaces as [TelegramError.Unknown] rather than masquerading as a network or auth
         * problem. The value itself is never shown to the user.
         */
        const val GENERIC_ERROR_CODE = 520
    }
}
