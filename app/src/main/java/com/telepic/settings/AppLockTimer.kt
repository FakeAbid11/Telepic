package com.telepic.settings

/**
 * Decides when an app that was backgrounded has to re-lock.
 *
 * Pure and clock-injected on purpose: the rule ("how long may this sit in the background before the
 * user is asked again?") is the whole feature, and it is the part that cannot be reasoned about from
 * a device test. Keeping it free of `Lifecycle`/`SystemClock` means it is covered by ordinary unit
 * tests instead.
 *
 * **The injected clock must be monotonic.** The caller measures an *elapsed* interval, so a wall
 * clock is the wrong instrument: an NTP correction or a manual clock change while the app is
 * backgrounded makes the difference negative, the elapsed time reads as below the grace window, and
 * the app does not re-lock. `SystemClock.elapsedRealtime()` is the correct source.
 *
 * The grace window is not zero, and that is load-bearing rather than a nicety. Launching a system
 * activity — the photo picker, the MediaStore delete-consent dialog, the Telegram link — *stops* this
 * activity. Locking on every return would demand a credential prompt in the middle of the user's own
 * flow, so a short grace window covers those round trips while still re-locking almost immediately
 * after the phone is actually put down.
 */
class AppLockTimer(
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
) {
    private var backgroundedAtMillis: Long? = null

    /** Records that the app went to the background at [nowMillis]. */
    fun onBackgrounded(nowMillis: Long) {
        backgroundedAtMillis = nowMillis
    }

    /**
     * Whether returning to the foreground at [nowMillis] must re-lock.
     *
     * Returns false when the app never went to the background (so an ordinary `ON_START` right after
     * `onCreate` cannot lock a user who just opened the app), and consumes the recorded time either
     * way — a single background trip may only ever produce a single lock decision.
     */
    fun onForegrounded(nowMillis: Long): Boolean {
        val backgroundedAt = backgroundedAtMillis ?: return false
        backgroundedAtMillis = null
        return nowMillis - backgroundedAt >= graceMillis
    }

    /** Forgets any recorded background time — used when the feature is switched off. */
    fun reset() {
        backgroundedAtMillis = null
    }

    companion object {
        /** How long the app may stay backgrounded before it re-locks. See the class KDoc. */
        const val DEFAULT_GRACE_MILLIS = 30_000L
    }
}
