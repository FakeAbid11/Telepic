package com.telepic.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telepic.di.AppContainer
import com.telepic.onboarding.OnboardingViewModel
import com.telepic.settings.ThemeViewModel
import com.telepic.telegram.TelegramAuthController
import com.telepic.ui.components.LoadingState
import com.telepic.ui.onboarding.OnboardingScreen
import com.telepic.ui.theme.TelepicTheme

/**
 * Root of the app. Applies the design system, then routes on persisted state:
 * loading → [LoadingState]; onboarding incomplete → [OnboardingScreen]; otherwise the main
 * application ([TelepicApp]). Onboarding never shows the bottom navigation, and completing it
 * persists and switches the startup destination to Photos.
 */
@Composable
fun TelepicRoot(
    themeViewModel: ThemeViewModel,
    onboardingViewModel: OnboardingViewModel,
    telegramController: TelegramAuthController,
    container: AppContainer,
) {
    val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
    val onboarding by onboardingViewModel.uiState.collectAsStateWithLifecycle()

    TelepicTheme(darkTheme = themeMode.isDarkTheme(isSystemInDarkTheme())) {
        when {
            onboarding.isLoading -> LoadingState()
            !onboarding.isCompleted -> OnboardingScreen(
                backupPreference = onboarding.backupPreference,
                telegramController = telegramController,
                onBackupPreferenceChange = onboardingViewModel::setBackupPreference,
                onComplete = onboardingViewModel::completeOnboarding,
            )
            else -> TelepicApp(
                themeMode = themeMode,
                onThemeModeChange = themeViewModel::setThemeMode,
                container = container,
            )
        }
    }
}
