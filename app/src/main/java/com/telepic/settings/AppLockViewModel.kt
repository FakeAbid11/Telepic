package com.telepic.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepic.diagnostics.TelepicLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the app-lock gate is currently doing. Explicit rather than a pair of booleans so that
 * "could not be determined" is a real state the UI must handle, not something that silently reads as
 * "no lock".
 */
enum class AppLockState {
    /** The persisted preference has not been read yet; nothing may be drawn. */
    Loading,

    /** The preference is off (or the device has no credential): content is shown. */
    Unlocked,

    /** The preference is on and the device is secure: content is hidden behind an auth prompt. */
    Locked,

    /**
     * The preference could not be read, so whether the lock is on is unknown. Content must NOT be
     * shown — failing open here would expose exactly what the lock hides — and the user is given an
     * explicit retry instead of being trapped on [Loading] or let through by accident.
     */
    RecoveryRequired,
}

/**
 * Owns the app-lock state: whether the app is currently locked, whether it can be enforced at all,
 * and whether a genuine storage failure needs recovery.
 *
 * Held at the activity level (like [ThemeViewModel]) because the lock has to sit *above* navigation —
 * a lock that lived inside a screen would be bypassed by pressing Back.
 *
 * [isDeviceSecure] is injected rather than read here so the decision is testable, and because it is
 * the honest gate on the whole feature: with no PIN, pattern or password configured there is nothing
 * to authenticate against, so the lock is reported unsupported instead of silently not locking.
 */
class AppLockViewModel(
    private val settingsRepository: SettingsRepository,
    private val isDeviceSecure: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val timer: AppLockTimer = AppLockTimer(),
) : ViewModel() {

    private val _state = MutableStateFlow(AppLockState.Loading)
    val state: StateFlow<AppLockState> = _state.asStateFlow()

    /**
     * Whether the window should be protected from screenshots/Recents (FLAG_SECURE). True whenever the
     * preference is on *or unreadable* — an unknown state is treated as "protect", never as "off".
     */
    private val _secureWindow = MutableStateFlow(false)
    val secureWindow: StateFlow<Boolean> = _secureWindow.asStateFlow()

    /** The lock is armed for the grace timer only when it is genuinely on and enforceable. */
    private val _enabled = MutableStateFlow(false)

    init {
        resolve()
    }

    /** Re-reads the persisted preference after a read failure. Re-enters [AppLockState.Loading]. */
    fun retry() {
        _state.value = AppLockState.Loading
        resolve()
    }

    private fun resolve() {
        viewModelScope.launch {
            val preference = try {
                // A bounded read: a storage layer that never answers must not strand the UI on
                // Loading forever — a timeout is treated as "unknown", which requires recovery.
                withTimeoutOrNull(READ_TIMEOUT_MILLIS) { settingsRepository.appLockPreference.first() }
                    ?: AppLockPreference.Unknown
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                TelepicLog.warn("AppLockViewModel", "app-lock state could not be read", throwable)
                AppLockPreference.Unknown
            }

            when (preference) {
                is AppLockPreference.Known -> {
                    val enforceable = preference.enabled && isDeviceSecure()
                    _enabled.value = enforceable
                    // FLAG_SECURE tracks the *preference*, not enforceability: a user who turned the
                    // lock on expects screenshots blocked even if the credential was later removed.
                    _secureWindow.value = preference.enabled
                    _state.value = if (enforceable) AppLockState.Locked else AppLockState.Unlocked
                }
                AppLockPreference.Unknown -> {
                    // Unknown must not fail open: protect the window and require an explicit retry.
                    _enabled.value = false
                    _secureWindow.value = true
                    _state.value = AppLockState.RecoveryRequired
                }
            }
        }
    }

    /** Records the moment the app left the foreground, so the grace window can be measured. */
    fun onBackgrounded() {
        if (!_enabled.value) return
        timer.onBackgrounded(now())
    }

    /** Re-locks if the app was backgrounded for longer than the grace window. */
    fun onForegrounded() {
        when (_state.value) {
            AppLockState.Loading, AppLockState.Locked, AppLockState.RecoveryRequired -> return
            AppLockState.Unlocked -> {
                if (!_enabled.value || !isDeviceSecure()) return
                if (timer.onForegrounded(now())) _state.value = AppLockState.Locked
            }
        }
    }

    /** Called only after a successful device-credential prompt. */
    fun unlock() {
        if (_state.value == AppLockState.Locked) _state.value = AppLockState.Unlocked
    }

    private companion object {
        /**
         * How long to wait for the persisted preference before treating it as unreadable. Generous —
         * a DataStore read is normally instant — so it only ever fires on a genuinely stuck store.
         */
        const val READ_TIMEOUT_MILLIS = 10_000L
    }
}
