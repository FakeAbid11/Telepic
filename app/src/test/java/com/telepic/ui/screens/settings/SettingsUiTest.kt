package com.telepic.ui.screens.settings

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.telepic.onboarding.BackupPreference
import com.telepic.onboarding.OnboardingRepository
import com.telepic.settings.AppLockPreference
import com.telepic.settings.BackupNetworkPreference
import com.telepic.settings.SettingsRepository
import com.telepic.settings.ThemeMode
import com.telepic.telegram.TelegramAuthController
import com.telepic.telegram.TelegramAuthState
import com.telepic.telegram.TelegramUser
import com.telepic.ui.theme.TelepicTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose-level coverage for Settings: the header/sections render, the account row states the
 * Telegram truth, and the navigation/theme affordances call the exact lambdas the nav graph wires
 * (the ViewModel's own behaviour is covered by [SettingsViewModelTest]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class SettingsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeAuthController : TelegramAuthController {
        private val _state = MutableStateFlow<TelegramAuthState>(TelegramAuthState.NotConnected)
        override val state: StateFlow<TelegramAuthState> = _state.asStateFlow()
        override val isBackendAvailable: Boolean = true
        override fun start() = Unit
        override fun submitPhoneNumber(phoneNumber: String) = Unit
        override fun submitCode(code: String) = Unit
        override fun submitPassword(password: String) = Unit
        override fun submitEmailAddress(emailAddress: String) = Unit
        override fun submitEmailCode(code: String) = Unit
        override fun resendEmailCode() = Unit
        override fun logout() = Unit
    }

    private class FakeOnboarding : OnboardingRepository {
        val completed = MutableStateFlow(true)
        val preference = MutableStateFlow<BackupPreference?>(null)
        val buckets = MutableStateFlow<Set<Long>>(emptySet())
        override val isCompleted: Flow<Boolean> get() = completed
        override val backupPreference: Flow<BackupPreference?> get() = preference
        override val backupBucketIds: Flow<Set<Long>> get() = buckets
        override suspend fun setCompleted(completed: Boolean) { this.completed.value = completed }
        override suspend fun setBackupPreference(preference: BackupPreference?) { this.preference.value = preference }
        override suspend fun setBackupBucketIds(ids: Set<Long>) { this.buckets.value = ids }
    }

    private class FakeSettings : SettingsRepository {
        val theme = MutableStateFlow(ThemeMode.DARK)
        val network = MutableStateFlow(BackupNetworkPreference.Default)
        val charging = MutableStateFlow(false)
        val batteryNotLow = MutableStateFlow(false)
        val videos = MutableStateFlow(true)
        val gifs = MutableStateFlow(true)
        val startMuted = MutableStateFlow(true)
        val appLock = MutableStateFlow(false)
        val biometric = MutableStateFlow(true)
        val backfillGranted = MutableStateFlow(false)

        override val themeMode: Flow<ThemeMode> get() = theme
        override suspend fun setThemeMode(mode: ThemeMode) { theme.value = mode }
        override val backupNetworkPreference: Flow<BackupNetworkPreference> get() = network
        override suspend fun setBackupNetworkPreference(preference: BackupNetworkPreference) { network.value = preference }
        override val backupRequiresCharging: Flow<Boolean> get() = charging
        override suspend fun setBackupRequiresCharging(required: Boolean) { charging.value = required }
        override val backupRequiresBatteryNotLow: Flow<Boolean> get() = batteryNotLow
        override suspend fun setBackupRequiresBatteryNotLow(required: Boolean) { batteryNotLow.value = required }
        override val showVideosInTimeline: Flow<Boolean> get() = videos
        override suspend fun setShowVideosInTimeline(show: Boolean) { videos.value = show }
        override val showGifsInTimeline: Flow<Boolean> get() = gifs
        override suspend fun setShowGifsInTimeline(show: Boolean) { gifs.value = show }
        override val startVideosMuted: Flow<Boolean> get() = startMuted
        override suspend fun setStartVideosMuted(muted: Boolean) { startMuted.value = muted }
        override val appLockEnabled: Flow<Boolean> get() = appLock
        override val appLockPreference: Flow<AppLockPreference> get() = appLock.map { AppLockPreference.Known(it) }
        override suspend fun setAppLockEnabled(enabled: Boolean) { appLock.value = enabled }
        override val appLockBiometricEnabled: Flow<Boolean> get() = biometric
        override suspend fun setAppLockBiometricEnabled(enabled: Boolean) { biometric.value = enabled }
        override val mediaLocationBackfillGranted: Flow<Boolean> get() = backfillGranted
        override suspend fun setMediaLocationBackfillGranted(granted: Boolean) { backfillGranted.value = granted }
    }

    private fun viewModel(
        auth: FakeAuthController = FakeAuthController(),
        settings: FakeSettings = FakeSettings(),
        deviceSecureReader: () -> Boolean = { false },
    ) = SettingsViewModel(
        telegramAuthController = auth,
        onboardingRepository = FakeOnboarding(),
        settingsRepository = settings,
        appContext = ApplicationProvider.getApplicationContext<Context>(),
        deviceSecureReader = deviceSecureReader,
    )

    private fun render(
        themeMode: ThemeMode = ThemeMode.LIGHT,
        onThemeModeChange: (ThemeMode) -> Unit = {},
        onOpenBackupCenter: () -> Unit = {},
        onOpenTrash: () -> Unit = {},
        onOpenTelegramLogin: () -> Unit = {},
        settings: FakeSettings = FakeSettings(),
        deviceSecureReader: () -> Boolean = { false },
    ) {
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                SettingsScreen(
                    viewModel = viewModel(settings = settings, deviceSecureReader = deviceSecureReader),
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onOpenBackupCenter = onOpenBackupCenter,
                    onOpenTrash = onOpenTrash,
                    onOpenTelegramLogin = onOpenTelegramLogin,
                )
            }
        }
    }

    @Test
    fun `header and account section state the telegram truth`() {
        render()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Account").assertIsDisplayed()
        composeRule.onNodeWithText("Telegram account").assertIsDisplayed()
        composeRule.onNodeWithText("Not connected").assertIsDisplayed()
        composeRule.onNodeWithText("Backup Center").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `backup center row opens the backup center`() {
        var opens = 0
        render(onOpenBackupCenter = { opens++ })
        composeRule.onNodeWithText("Backup Center").performScrollTo().performClick()
        assertEquals(1, opens)
    }

    @Test
    fun `trash row opens trash`() {
        var opens = 0
        render(onOpenTrash = { opens++ })
        composeRule.onNodeWithText("Trash").performScrollTo().performClick()
        assertEquals(1, opens)
    }

    @Test
    fun `disconnected telegram row opens the login route`() {
        var opens = 0
        render(onOpenTelegramLogin = { opens++ })
        composeRule.onNodeWithText("Telegram account").performScrollTo().performClick()
        assertEquals(1, opens)
    }

    @Test
    fun `theme dialog reports the chosen mode`() {
        var chosen: ThemeMode? = null
        render(themeMode = ThemeMode.LIGHT, onThemeModeChange = { chosen = it })
        composeRule.onNodeWithText("Theme").performScrollTo().performClick()
        composeRule.onNodeWithText("Choose theme").assertIsDisplayed()
        composeRule.onNodeWithText("Dark").performClick()
        assertEquals(ThemeMode.DARK, chosen)
    }

    // The regroup: theme and the timeline toggles share one "Display" header, and the thin
    // single-purpose section headers they replaced are gone (their rows moved into Backup).
    @Test
    fun `display section groups the theme with the timeline toggles and retires the old headers`() {
        render()
        composeRule.onNodeWithText("Display").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Theme").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Show videos").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("On this device").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Library").assertDoesNotExist()
        composeRule.onNodeWithText("Media").assertDoesNotExist()
        composeRule.onNodeWithText("Appearance").assertDoesNotExist()
    }

    // Fingerprint unlock sits under the lock it modifies, and is only unusable when there is nothing
    // to authenticate against at all.
    @Test
    fun `the fingerprint row is unavailable without a device credential`() {
        render()
        composeRule.onNodeWithText("Fingerprint unlock").performScrollTo().assertIsNotEnabled()
        composeRule
            .onNodeWithText("Set a device PIN, pattern or password to use fingerprint unlock.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the fingerprint row is on by default and persists being switched off`() {
        val settings = FakeSettings()
        render(settings = settings, deviceSecureReader = { true })

        composeRule.onNodeWithText("Fingerprint unlock").performScrollTo().assertIsOn()
        composeRule.onNodeWithText("Fingerprint unlock").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertFalse(settings.biometric.value)
    }
}
