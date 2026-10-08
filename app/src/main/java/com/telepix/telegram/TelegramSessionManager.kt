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
    private var started = false
    private var parametersSent = false

    @Synchronized
    fun initialize() {
        if (started) return
        started = true
        _state.value = TelegramAuthState.Initializing

        scope.launch {
            try {
                gateway.open()
            } catch (throwable: Throwable) {
                _state.value = TelegramAuthState.Failed(
                    TelegramError.Initialization("Telegram engine could not start."),
                )
                return@launch
            }
            gateway.updates.collect { update -> handle(update) }
        }
    }

    private suspend fun handle(update: TdApi.Object) {
        if (update is TdApi.UpdateAuthorizationState) {
            onAuthorizationState(update.authorizationState)
        }
    }

    private suspend fun onAuthorizationState(state: TdApi.AuthorizationState) {
        lastAuthorizationState = state
        if (state is TdApi.AuthorizationStateWaitTdlibParameters && !parametersSent) {
            parametersSent = true
            sendParameters()
        }
        publishMappedState()
        if (state is TdApi.AuthorizationStateReady && authorizedUser.value == null) {
            fetchAccount()
        }
    }

    private suspend fun sendParameters() {
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
        started = false
        parametersSent = false
    }
}
