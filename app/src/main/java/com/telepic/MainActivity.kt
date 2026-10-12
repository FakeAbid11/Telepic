package com.telepic

import android.app.KeyguardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.telepic.onboarding.OnboardingViewModel
import com.telepic.settings.AppLockViewModel
import com.telepic.settings.ThemeViewModel
import com.telepic.ui.TelepicRoot

/**
 * Single-activity host. Wires the DI container into the theme/onboarding/app-lock ViewModels and
 * hands off all UI to [TelepicRoot], which handles startup routing and the lock gate.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 (targetSdk 35) enforces edge-to-edge; the Material Scaffold and the onboarding
        // frame consume the system-bar insets, so content never hides behind them.
        enableEdgeToEdge()
        // Resolved once per activity: the app lock is only meaningful on a device that actually has a
        // PIN/pattern/password to authenticate against, and that cannot change while we are running.
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        setContent {
            val container = (application as TelepicApplication).container

            val themeFactory = remember(container) {
                viewModelFactory {
                    initializer { ThemeViewModel(container.settingsRepository) }
                }
            }
            val themeViewModel: ThemeViewModel = viewModel(factory = themeFactory)
            val onboardingFactory = remember(container) {
                viewModelFactory {
                    initializer {
                        OnboardingViewModel(
                            repository = container.onboardingRepository,
                            albumRepository = container.albumRepository,
                            // Apply a first-run or changed backup choice immediately instead of only at next launch.
                            onBackupChoiceChanged = { container.backupCoordinator.syncFromPreference() },
                        )
                    }
                }
            }
            val onboardingViewModel: OnboardingViewModel = viewModel(factory = onboardingFactory)
            val appLockFactory = remember(container, keyguardManager) {
                viewModelFactory {
                    initializer {
                        AppLockViewModel(
                            settingsRepository = container.settingsRepository,
                            isDeviceSecure = { keyguardManager?.isDeviceSecure == true },
                        )
                    }
                }
            }
            val appLockViewModel: AppLockViewModel = viewModel(factory = appLockFactory)

            TelepicRoot(
                themeViewModel = themeViewModel,
                onboardingViewModel = onboardingViewModel,
                appLockViewModel = appLockViewModel,
                telegramController = container.telegramAuthController,
                container = container,
            )
        }
    }
}
