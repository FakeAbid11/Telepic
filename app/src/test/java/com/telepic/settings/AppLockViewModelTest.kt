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
        private val throwOnRead: Boolean = false,
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
}
