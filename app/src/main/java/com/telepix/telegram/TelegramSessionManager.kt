package com.telepix.telegram

import android.content.Context
import android.os.Build
import java.io.File
import java.util.Locale
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
    private var collectorJob: kotlinx.coroutines.Job? = null
    private var parametersSent = false

    /**
     * Begin (or re-attempt) authentication. Idempotent and safe to call again after a
     * [TelegramAuthState.Failed]: the update collector is started at most once, the client is opened
     * only if not already open, and the flow is re-driven from TDLib's *current* authorization state —
     * so a login whose `SetTdlibParameters` was rejected can recover without an app restart (TDLib does
     * not re-announce that wait on its own).
     */
    @Synchronized
    fun initialize() {
        _state.value = TelegramAuthState.Initializing
        scope.launch {
            val isRetry = collectorJob != null
            if (collectorJob == null) {
                // Subscribe to updates BEFORE opening the client, so the initial authorization update
                // (emitted during/right after open()) is received, not lost. The gateway also replays the
                // latest update as a second guard against the open/subscribe ordering race.
                collectorJob = launch { gateway.updates.collect { update -> handle(update) } }
                try {
                    gateway.open()
                } catch (throwable: Throwable) {
                    collectorJob?.cancel()
                    collectorJob = null
                    _state.value = TelegramAuthState.Failed(
                        TelegramError.Initialization("Telegram engine could not start."),
                    )
                    return@launch
                }
                // First start: opening makes TDLib emit the initial authorization state, which the
                // collector handles. No need (and no race) from also querying it here.
            } else if (isRetry) {
                // Retry after a failure: the client is already open and TDLib will NOT re-announce the
                // current wait (e.g. after a rejected SetTdlibParameters), so re-drive from its state.
                driveFromCurrentState()
            }
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
                    username = null,
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
                is TdApi.Error -> fail(
                    TelegramError.classify(response.code, response.message, TelegramError.AuthStage.PhoneNumber),
                )
                else -> Unit
            }
        }
    }

    fun submitCode(code: String) {
        scope.launch {
            when (val response = safeRequest(TdApi.CheckAuthenticationCode(code.trim()))) {
                is TdApi.Error -> fail(
                    TelegramError.classify(response.code, response.message, TelegramError.AuthStage.Code),
                )
                else -> Unit
            }
        }
    }

    fun submitPassword(password: String) {
        scope.launch {
            // The password is passed straight to TDLib and never stored or logged.
            when (val response = safeRequest(TdApi.CheckAuthenticationPassword(password))) {
                is TdApi.Error -> fail(
                    TelegramError.classify(response.code, response.message, TelegramError.AuthStage.Password),
                )
                else -> Unit
            }
        }
    }

    fun logout() {
        scope.launch {
            safeRequest(TdApi.LogOut())
        }
    }

    private suspend fun safeRequest(request: TdApi.Function<*>): TdApi.Object? =
        try {
            gateway.request(request)
        } catch (throwable: Throwable) {
            null
        }

    private fun fail(error: TelegramError) {
        _state.value = TelegramAuthState.Failed(error)
    }

    private fun publishMappedState() {
        _state.value = AuthorizationStateMapper.map(lastAuthorizationState, authorizedUser.value)
    }

    fun close() {
        gateway.close()
        scope.cancel()
        collectorJob = null
        parametersSent = false
    }
}
