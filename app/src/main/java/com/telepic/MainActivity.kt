package com.telepic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.telepic.onboarding.OnboardingViewModel
import com.telepic.settings.ThemeViewModel
import com.telepic.ui.TelepicRoot

/**
 * Single-activity host. Wires the DI container into the theme/onboarding ViewModels and
 * hands off all UI to [TelepicRoot], which handles startup routing.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 (targetSdk 35) enforces edge-to-edge; the Material Scaffold and the onboarding
        // frame consume the system-bar insets, so content never hides behind them.
        enableEdgeToEdge()
        setContent {
            val container = (application as TelepicApplication).container

            val themeViewModel: ThemeViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { ThemeViewModel(container.settingsRepository) }
                },
            )
            val onboardingViewModel: OnboardingViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        OnboardingViewModel(
                            repository = container.onboardingRepository,
                            albumRepository = container.albumRepository,
                            // Apply a first-run or changed backup choice immediately instead of only at next launch.
                            onBackupChoiceChanged = { container.backupCoordinator.syncFromPreference() },
                        )
                    }
                },
            )

            TelepicRoot(
                themeViewModel = themeViewModel,
                onboardingViewModel = onboardingViewModel,
                telegramController = container.telegramAuthController,
                container = container,
            )
        }
    }
}
