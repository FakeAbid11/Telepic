package com.telepic.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.telepic.R
import com.telepic.di.AppContainer
import com.telepic.onboarding.BackupPreference
import com.telepic.onboarding.OnboardingViewModel
import com.telepic.settings.AppLockState
import com.telepic.settings.AppLockViewModel
import com.telepic.settings.ThemeViewModel
import com.telepic.telegram.TelegramAuthController
import com.telepic.ui.components.LoadingState
import com.telepic.ui.onboarding.OnboardingScreen
import com.telepic.ui.theme.TelepicTheme

/**
 * Root of the app. Applies the design system, then routes on persisted state:
 * app lock loading → [LoadingState]; unreadable lock state → [AppLockRecoveryScreen]; locked →
 * [AppLockScreen]; onboarding loading → [LoadingState]; onboarding incomplete → [OnboardingScreen];
 * otherwise the main application ([TelepicApp]). Onboarding never shows the bottom navigation, and
 * completing it persists and switches the startup destination to Photos.
 *
 * The lock is checked *before* the onboarding branch because it has to cover the whole app, not just
 * the main shell — and it waits for the lock state to resolve so the content it exists to hide is
 * never drawn for even one frame. When the state cannot be determined the app fails closed rather
 * than assuming the lock is off.
 */
@Composable
fun TelepicRoot(
    themeViewModel: ThemeViewModel,
    onboardingViewModel: OnboardingViewModel,
    appLockViewModel: AppLockViewModel,
    telegramController: TelegramAuthController,
    container: AppContainer,
) {
    val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
    val onboarding by onboardingViewModel.uiState.collectAsStateWithLifecycle()
    val lockState by appLockViewModel.state.collectAsStateWithLifecycle()

    // The grace window is measured from the activity lifecycle rather than from composition: leaving
    // the app does not dispose this composable, so ON_STOP is the only signal that the app really went
    // to the background (see AppLockTimer for why a system-activity round trip must not re-lock).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> appLockViewModel.onBackgrounded()
                Lifecycle.Event.ON_START -> appLockViewModel.onForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Created here rather than inside the branch below. While locked, the app's content must not be
    // composed at all (see AppLockScreen: nothing may exist behind the lock to be read by TalkBack),
    // so `locked` swaps TelepicApp out of the tree — and a controller remembered *there* would go with
    // it, dropping the back stack, the current tab and every NavBackStackEntry's own saved state on
    // every re-lock. Hoisted, it outlives the composition it drives.
    //
    // Not composing the content is necessary but not sufficient: it stops the content being *read*
    // from the live view tree, not from the system's Recents snapshot. That half is FLAG_SECURE, set
    // in MainActivity while the lock preference is on.
    val navController = rememberNavController()

    TelepicTheme(darkTheme = themeMode.isDarkTheme(isSystemInDarkTheme())) {
        when {
            lockState == AppLockState.Loading -> LoadingState(message = stringResource(R.string.onboarding_loading))
            // Could not read the lock state: fail CLOSED (never assume "off") and offer a retry.
            lockState == AppLockState.RecoveryRequired -> AppLockRecoveryScreen(onRetry = appLockViewModel::retry)
            lockState == AppLockState.Locked -> AppLockScreen(onUnlocked = appLockViewModel::unlock)
            onboarding.isLoading -> LoadingState(message = stringResource(R.string.onboarding_loading))
            !onboarding.isCompleted -> {
                val folders by onboardingViewModel.folders.collectAsStateWithLifecycle()
                val foldersLoaded by onboardingViewModel.foldersLoaded.collectAsStateWithLifecycle()
                val foldersFailed by onboardingViewModel.foldersFailed.collectAsStateWithLifecycle()
                val setupFailed by onboardingViewModel.setupFailed.collectAsStateWithLifecycle()
                val completionFailed by onboardingViewModel.completionFailed.collectAsStateWithLifecycle()
                OnboardingScreen(
                    backupPreference = onboarding.backupPreference,
                    backupBucketIds = onboarding.backupBucketIds,
                    folders = folders,
                    foldersLoaded = foldersLoaded,
                    foldersFailed = foldersFailed,
                    backupSetupFailed = setupFailed,
                    completionFailed = completionFailed,
                    telegramController = telegramController,
                    onBackupPreferenceChange = onboardingViewModel::setBackupPreference,
                    onNeedFolders = onboardingViewModel::loadFolders,
                    onPickFolders = { ids -> onboardingViewModel.setBackupChoice(BackupPreference.SELECT_FOLDER, ids) },
                    onComplete = onboardingViewModel::completeOnboarding,
                )
            }
            else -> TelepicApp(
                themeMode = themeMode,
                onThemeModeChange = themeViewModel::setThemeMode,
                container = container,
                navController = navController,
            )
        }
    }
}
