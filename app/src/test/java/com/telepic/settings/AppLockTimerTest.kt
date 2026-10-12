package com.telepic.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app lock's whole rule is *when* to re-lock, and it is the part that cannot be reasoned about
 * from a device test — a device can only show the two endpoints. The timer is clock-injected, so the
 * interesting cases (a system-activity round trip, the grace boundary, a second ON_START) are ordinary
 * assertions here.
 */
class AppLockTimerTest {

    @Test
    fun `returning without having backgrounded never locks`() {
        val timer = AppLockTimer(graceMillis = 1_000L)
        // onCreate → ON_START with no intervening ON_STOP must not lock a user who just opened the app.
        assertFalse(timer.onForegrounded(nowMillis = 5_000L))
    }

    @Test
    fun `a background trip inside the grace window does not lock`() {
        val timer = AppLockTimer(graceMillis = 30_000L)
        timer.onBackgrounded(nowMillis = 1_000L)
        // The photo picker, the delete-consent dialog and the Telegram link all stop this activity;
        // locking on every return would demand a credential prompt mid-flow.
        assertFalse(timer.onForegrounded(nowMillis = 1_000L + 29_999L))
    }

    @Test
    fun `a background trip at the grace boundary locks`() {
        val timer = AppLockTimer(graceMillis = 30_000L)
        timer.onBackgrounded(nowMillis = 1_000L)
        assertTrue(timer.onForegrounded(nowMillis = 1_000L + 30_000L))
    }

    @Test
    fun `a single background trip only ever produces one lock decision`() {
        val timer = AppLockTimer(graceMillis = 1_000L)
        timer.onBackgrounded(nowMillis = 0L)
        assertTrue(timer.onForegrounded(nowMillis = 5_000L))
        // The recorded time is consumed, so a second ON_START without a new ON_STOP cannot re-lock.
        assertFalse(timer.onForegrounded(nowMillis = 9_000L))
    }

    @Test
    fun `reset forgets the recorded background time`() {
        val timer = AppLockTimer(graceMillis = 1_000L)
        timer.onBackgrounded(nowMillis = 0L)
        timer.reset()
        assertFalse(timer.onForegrounded(nowMillis = 10_000L))
    }

    @Test
    fun `the default grace window is not zero`() {
        // Zero would mean locking on every system-activity round trip, which is the defect the
        // window exists to prevent.
        assertTrue(AppLockTimer.DEFAULT_GRACE_MILLIS > 0L)
    }
}
