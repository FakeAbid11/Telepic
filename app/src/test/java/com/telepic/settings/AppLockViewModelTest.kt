package com.telepic.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app-lock gate. The load-bearing guarantee here is that an unreadable preference does NOT fail
 * open (which would expose the library the lock protects) — everything else is state mapping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppLockViewModelTest {

    private class FakeSettings(
        initial: AppLockPreference = AppLockPreference.Known(false),
        var throwOnRead: Boolean = false,
    ) : SettingsRepository {
        val lock = MutableStateFlow(initial)

        override val appLockPreference: Flow<AppLockPreference>
            get() = if (throwOnRead) flow { error("data store unavailable") } else lock

        override val appLockEnabled: Flow<Boolean> get() = lock.map { (it as? AppLockPreference.Known)?.enabled ?: false }
        override suspend fun setAppLockEnabled(enabled: Boolean) { lock.value = AppLockPreference.Known(enabled) }

        override val themeMode: Flow<ThemeMode> = flowOf(ThemeMode.Default)
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override val backupNetworkPreference: Flow<BackupNetworkPreference> = flowOf(BackupNetworkPreference.Default)
        override suspend fun setBackupNetworkPreference(preference: BackupNetworkPreference) = Unit
        override val backupRequiresCharging: Flow<Boolean> = flowOf(false)
        override suspend fun setBackupRequiresCharging(required: Boolean) = Unit
        override val backupRequiresBatteryNotLow: Flow<Boolean> = flowOf(false)
        override suspend fun setBackupRequiresBatteryNotLow(required: Boolean) = Unit
        override val showVideosInTimeline: Flow<Boolean> = flowOf(true)
        override suspend fun setShowVideosInTimeline(show: Boolean) = Unit
        override val showGifsInTimeline: Flow<Boolean> = flowOf(true)
        override suspend fun setShowGifsInTimeline(show: Boolean) = Unit
        override val startVideosMuted: Flow<Boolean> = flowOf(true)
        override suspend fun setStartVideosMuted(muted: Boolean) = Unit
        override val mediaLocationBackfillGranted: Flow<Boolean> = flowOf(false)
        override suspend fun setMediaLocationBackfillGranted(granted: Boolean) = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        settings: SettingsRepository,
        deviceSecure: Boolean = true,
        clock: () -> Long = { 0L },
        timer: AppLockTimer = AppLockTimer(),
    ) = AppLockViewModel(settings, isDeviceSecure = { deviceSecure }, now = clock, timer = timer)

    @Test
    fun `first launch with the preference off is unlocked and unprotected`() = runTest {
        val vm = viewModel(FakeSettings(AppLockPreference.Known(false)))
        assertEquals(AppLockState.Unlocked, vm.state.value)
        assertFalse(vm.secureWindow.value)
    }

    @Test
    fun `preference on with a secure device starts locked and protected`() = runTest {
        val vm = viewModel(FakeSettings(AppLockPreference.Known(true)), deviceSecure = true)
        assertEquals(AppLockState.Locked, vm.state.value)
        assertTrue(vm.secureWindow.value)
    }

    @Test
    fun `preference on but device not secure is unlocked yet still protected`() = runTest {
        val vm = viewModel(FakeSettings(AppLockPreference.Known(true)), deviceSecure = false)
        assertEquals(AppLockState.Unlocked, vm.state.value)
        assertTrue(vm.secureWindow.value)
    }

    @Test
    fun `an unreadable preference requires recovery and never fails open`() = runTest {
        val vm = viewModel(FakeSettings(throwOnRead = true), deviceSecure = true)
        assertEquals(AppLockState.RecoveryRequired, vm.state.value)
        assertTrue("an unknown lock state must still protect the window", vm.secureWindow.value)
    }

    @Test
    fun `retry resolves recovery once the preference becomes readable`() = runTest {
        val settings = FakeSettings(AppLockPreference.Unknown)
        val vm = viewModel(settings)
        assertEquals(AppLockState.RecoveryRequired, vm.state.value)

        settings.lock.value = AppLockPreference.Known(false)
        vm.retry()

        assertEquals(AppLockState.Unlocked, vm.state.value)
    }

    @Test
    fun `retry with the lock enabled requires authentication and does not fail open`() = runTest {
        val settings = FakeSettings(AppLockPreference.Unknown, throwOnRead = true)
        val vm = viewModel(settings, deviceSecure = true)
        assertEquals(AppLockState.RecoveryRequired, vm.state.value)
        assertTrue(vm.secureWindow.value)

        // The read recovers, but resolving the preference is not authentication: the gate must
        // remain Locked until the credential prompt reports success.
        settings.throwOnRead = false
        settings.lock.value = AppLockPreference.Known(true)
        vm.retry()

        assertEquals(AppLockState.Locked, vm.state.value)
        assertTrue(vm.secureWindow.value)

        vm.unlock()
        assertEquals(AppLockState.Unlocked, vm.state.value)
    }

    @Test
    fun `repeated read failures from recovery stay in recovery`() = runTest {
        val settings = FakeSettings(AppLockPreference.Unknown, throwOnRead = true)
        val vm = viewModel(settings, deviceSecure = true)
        assertEquals(AppLockState.RecoveryRequired, vm.state.value)

        vm.retry()
        assertEquals(AppLockState.RecoveryRequired, vm.state.value)
        assertTrue(vm.secureWindow.value)

        vm.retry()
        assertEquals(AppLockState.RecoveryRequired, vm.state.value)
    }

    @Test
    fun `retry resumes observing preference changes after recovery`() = runTest {
        val settings = FakeSettings(AppLockPreference.Unknown, throwOnRead = true)
        val vm = viewModel(settings, deviceSecure = true)
        assertEquals(AppLockState.RecoveryRequired, vm.state.value)

        settings.throwOnRead = false
        settings.lock.value = AppLockPreference.Known(true)
        vm.retry()
        assertEquals(AppLockState.Locked, vm.state.value)

        // The lock is disabled in Settings while the app is running: the recovered collector must
        // see it rather than leaving the user on the lock screen until a restart.
        settings.lock.value = AppLockPreference.Known(false)
        assertEquals(AppLockState.Unlocked, vm.state.value)
        assertFalse(vm.secureWindow.value)
    }

    @Test
    fun `cold start with the lock enabled stays locked until authentication`() = runTest {
        val vm = viewModel(FakeSettings(AppLockPreference.Known(true)), deviceSecure = true)
        assertEquals(AppLockState.Locked, vm.state.value)

        // A preference emission is not a credential result.
        vm.onForegrounded()
        assertEquals(AppLockState.Locked, vm.state.value)

        vm.unlock()
        assertEquals(AppLockState.Unlocked, vm.state.value)
    }

    @Test
    fun `unlock releases the gate`() = runTest {
        val vm = viewModel(FakeSettings(AppLockPreference.Known(true)), deviceSecure = true)
        assertEquals(AppLockState.Locked, vm.state.value)
        vm.unlock()
        assertEquals(AppLockState.Unlocked, vm.state.value)
    }

    @Test
    fun `returning beyond the grace window re-locks`() = runTest {
        var now = 0L
        val vm = viewModel(
            FakeSettings(AppLockPreference.Known(true)),
            deviceSecure = true,
            clock = { now },
            timer = AppLockTimer(graceMillis = 1_000L),
        )
        vm.unlock()
        assertEquals(AppLockState.Unlocked, vm.state.value)

        vm.onBackgrounded()
        now = 5_000L
        vm.onForegrounded()

        assertEquals(AppLockState.Locked, vm.state.value)
    }

    @Test
    fun `returning within the grace window stays unlocked`() = runTest {
        var now = 0L
        val vm = viewModel(
            FakeSettings(AppLockPreference.Known(true)),
            deviceSecure = true,
            clock = { now },
            timer = AppLockTimer(graceMillis = 1_000L),
        )
        vm.unlock()

        vm.onBackgrounded()
        now = 200L
        vm.onForegrounded()

        assertEquals(AppLockState.Unlocked, vm.state.value)
    }

    @Test
    fun `recovery is not cleared by a foreground transition`() = runTest {
        val vm = viewModel(FakeSettings(throwOnRead = true), deviceSecure = true)
        vm.onBackgrounded()
        vm.onForegrounded()
        assertEquals(AppLockState.RecoveryRequired, vm.state.value)
    }

    @Test
    fun `turning the lock on while the app is running arms it and protects the window`() = runTest {
        // The preference used to be resolved once, at construction, so the Settings switch did
        // nothing for the rest of the session: the app never locked and FLAG_SECURE was never
        // applied, so the Recents snapshot still showed the library.
        val settings = FakeSettings(AppLockPreference.Known(false))
        val vm = viewModel(settings, deviceSecure = true)
        assertEquals(AppLockState.Unlocked, vm.state.value)
        assertFalse(vm.secureWindow.value)

        settings.lock.value = AppLockPreference.Known(true)

        assertTrue("the window must be protected once the lock is on", vm.secureWindow.value)
        // Not locked *yet*: the Settings row promises the lock fires on the next foregrounding, and
        // locking the user out mid-Settings would be a surprise.
        assertEquals(AppLockState.Unlocked, vm.state.value)
    }

    @Test
    fun `turning the lock off while the app is running unlocks it`() = runTest {
        val settings = FakeSettings(AppLockPreference.Known(true))
        val vm = viewModel(settings, deviceSecure = true)
        assertEquals(AppLockState.Locked, vm.state.value)

        settings.lock.value = AppLockPreference.Known(false)

        assertEquals(AppLockState.Unlocked, vm.state.value)
        assertFalse(vm.secureWindow.value)
    }
}
