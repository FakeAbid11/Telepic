package com.telepic.settings

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepic.diagnostics.TelepicLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private val now: () -> Long = SystemClock::elapsedRealtime,
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

    /**
     * The last observed preference.
     *
     * Kept so [refreshEnforceability] can re-evaluate the *device credential* without re-applying
     * the lock state — the biometric prompt is itself an activity, so re-applying the state on every
     * foreground would re-lock the user the instant they unlocked.
     */
    private var lastPreference: AppLockPreference = AppLockPreference.Unknown

    /**
     * The live preference collector, if one is attached.
     *
     * A read failure ends the collector. [retry] starts a new one after a successful read so a
     * recovered app keeps seeing Settings toggles; without this the lock was resolved once and then
     * silently stopped observing.
     */
    private var preferenceJob: Job? = null

    init {
        // The preference is **observed**, not read once. The first read is bounded so a storage
        // layer that never answers cannot strand the UI on Loading forever; a failure enters
        // RecoveryRequired and only an explicit retry re-subscribes.
        viewModelScope.launch {
            val initial = loadPreference()
            apply(initial)
            if (initial !is AppLockPreference.Unknown) observePreference()
        }
    }

    /**
     * Re-reads the persisted preference after a read failure and resumes observation.
     *
     * No-op unless the gate is actually in [AppLockState.RecoveryRequired]; rapid taps while the
     * read is in flight must not start competing collectors.
     */
    fun retry() {
        if (_state.value != AppLockState.RecoveryRequired) return
        _state.value = AppLockState.Loading
        viewModelScope.launch {
            val preference = loadPreference()
            apply(preference)
            if (preference !is AppLockPreference.Unknown) observePreference()
        }
    }

    /**
     * One bounded read of the persisted preference.
     *
     * A timeout is treated as "unknown", which requires recovery. Cancellation of the ViewModel
     * scope is rethrown; every other failure is reported and mapped to Unknown.
     */
    private suspend fun loadPreference(): AppLockPreference = try {
        withTimeoutOrNull(READ_TIMEOUT_MILLIS) { settingsRepository.appLockPreference.first() }
            ?: AppLockPreference.Unknown
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        TelepicLog.warn("AppLockViewModel", "app-lock state could not be read", throwable)
        AppLockPreference.Unknown
    }

    /** Attach a fresh collector; a previous one is cancelled so only one preference read wins. */
    private fun observePreference() {
        preferenceJob?.cancel()
        preferenceJob = viewModelScope.launch {
            try {
                settingsRepository.appLockPreference.collect { preference -> apply(preference) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                TelepicLog.warn("AppLockViewModel", "the app-lock preference could not be observed", throwable)
                apply(AppLockPreference.Unknown)
            }
        }
    }

    /** Map one observed preference onto the lock's state, the window flag and the grace timer. */
    private fun apply(preference: AppLockPreference) {
        val previous = _state.value
        lastPreference = preference
        when (preference) {
            is AppLockPreference.Known -> {
                val enforceable = preference.enabled && isDeviceSecure()
                _enabled.value = enforceable
                // FLAG_SECURE tracks the *preference*, not enforceability: a user who turned the
                // lock on expects screenshots blocked even if the credential was later removed.
                _secureWindow.value = preference.enabled
                _state.value = when {
                    // The lock is off, or the device cannot enforce it: the content is visible.
                    !enforceable -> AppLockState.Unlocked
                    // An already-unlocked screen stays unlocked. This is either the user who just
                    // passed the credential prompt, or the user arming the lock from Settings,
                    // which by contract fires on the next foregrounding rather than mid-screen.
                    previous == AppLockState.Unlocked -> AppLockState.Unlocked
                    // Loading (cold start or retry), RecoveryRequired, and Locked all require the
                    // credential. Resolving the preference is not authentication.
                    else -> AppLockState.Locked
                }
                // The lock was switched off: forget any background trip, so the grace timer cannot
                // re-lock an app the user just unlocked by turning the feature off.
                if (!preference.enabled) timer.reset()
            }
            AppLockPreference.Unknown -> {
                // Unknown must not fail open: protect the window and require an explicit retry.
                _enabled.value = false
                _secureWindow.value = true
                _state.value = AppLockState.RecoveryRequired
            }
        }
    }

    /**
     * Re-evaluate whether the lock is *enforceable* right now.
     *
     * The device credential can be added or removed while Telepic is open, so the gate cannot trust
     * the launch-time read of it. The lock **state** is deliberately not touched here: the biometric
     * prompt is itself an activity, so it stops and restarts this one, and re-applying the state
     * would re-lock the user the instant they unlocked.
     */
    private fun refreshEnforceability() {
        val preference = lastPreference
        if (preference !is AppLockPreference.Known) return
        _enabled.value = preference.enabled && isDeviceSecure()
    }

    /** Records the moment the app left the foreground, so the grace window can be measured. */
    fun onBackgrounded() {
        if (!_enabled.value) return
        timer.onBackgrounded(now())
    }

    /** Re-locks if the app was backgrounded for longer than the grace window. */
    fun onForegrounded() {
        // The device credential can be added or removed while Telepic is open, so the gate is
        // re-evaluated here rather than trusted from the launch-time read.
        refreshEnforceability()
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
