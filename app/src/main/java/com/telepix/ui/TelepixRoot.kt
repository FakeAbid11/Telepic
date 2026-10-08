package com.telepix.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telepix.di.AppContainer
import com.telepix.onboarding.OnboardingViewModel
import com.telepix.settings.ThemeViewModel
import com.telepix.telegram.TelegramAuthController
import com.telepix.ui.components.LoadingState
import com.telepix.ui.onboarding.OnboardingScreen
import com.telepix.ui.theme.TelepixTheme

/**
 * Root of the app. Applies the design system, then routes on persisted state:
 * loading → [LoadingState]; onboarding incomplete → [OnboardingScreen]; otherwise the main
 * application ([TelepixApp]). Onboarding never shows the bottom navigation, and completing it
 * persists and switches the startup destination to Photos.
 */
@Composable
fun TelepixRoot(
    themeViewModel: ThemeViewModel,
    onboardingViewModel: OnboardingViewModel,
    telegramController: TelegramAuthController,
    container: AppContainer,
) {
    val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
    val onboarding by onboardingViewModel.uiState.collectAsStateWithLifecycle()
    val telegramState by telegramController.state.collectAsStateWithLifecycle()

    TelepixTheme(darkTheme = themeMode.isDarkTheme(isSystemInDarkTheme())) {
        when {
            onboarding.isLoading -> LoadingState()
            !onboarding.isCompleted -> OnboardingScreen(
                backupPreference = onboarding.backupPreference,
                telegramState = telegramState,
                telegramBackendAvailable = telegramController.isBackendAvailable,
                onTelegramConnect = telegramController::connect,
                onBackupPreferenceChange = onboardingViewModel::setBackupPreference,
                onComplete = onboardingViewModel::completeOnboarding,
            )
            else -> TelepixApp(
                themeMode = themeMode,
                onThemeModeChange = themeViewModel::setThemeMode,
                container = container,
            )
        }
    }
}
