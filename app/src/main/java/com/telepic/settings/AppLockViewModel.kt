package com.telepic.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the app-lock state: whether the app is currently locked, and whether the feature can be
 * enforced at all on this device.
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

    /** Whether the user's app-lock preference is on. */
    val enabled: StateFlow<Boolean> = settingsRepository.appLockEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * True once the persisted preference has been read.
     *
     * The root gate waits for this before drawing anything. Without it the first frame would render
     * unlocked and then lock a moment later — for a privacy feature, a visible flash of the content
     * it exists to hide is a defect, not a cosmetic wart.
     */
    private val _resolved = MutableStateFlow(false)
    val resolved: StateFlow<Boolean> = _resolved.asStateFlow()

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    init {
        viewModelScope.launch {
            _locked.value = try {
                // The repository's flow already degrades a read error to `false`; this boundary is
                // the same rule the rest of the app follows — nothing escapes `viewModelScope`.
                settingsRepository.appLockEnabled.first() && isDeviceSecure()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                false
            }
            _resolved.value = true
        }
    }

    /** Records the moment the app left the foreground, so the grace window can be measured. */
    fun onBackgrounded() {
        if (!enabled.value) return
        timer.onBackgrounded(now())
    }

    /** Re-locks if the app was backgrounded for longer than the grace window. */
    fun onForegrounded() {
        if (_locked.value) return
        if (!enabled.value || !isDeviceSecure()) return
        if (timer.onForegrounded(now())) _locked.value = true
    }

    /** Called only after a successful device-credential prompt. */
    fun unlock() {
        _locked.value = false
    }

    /**
     * Applies a change to the preference immediately.
     *
     * Switching the lock *off* while it is on screen must release it — otherwise turning the feature
     * off from Settings would leave the user staring at a lock screen they had just disabled.
     */
    fun onPreferenceChanged(enabledNow: Boolean) {
        if (!enabledNow) {
            timer.reset()
            _locked.value = false
        }
    }
}
