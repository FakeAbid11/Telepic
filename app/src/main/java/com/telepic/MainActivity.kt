package com.telepic

import android.app.KeyguardManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 *
 * Extends `FragmentActivity` rather than `ComponentActivity` because the app-lock's
 * `BiometricPrompt` requires a `FragmentActivity` host. `FragmentActivity` is a `ComponentActivity`,
 * so `setContent` / `enableEdgeToEdge` below are unaffected.
 */
class MainActivity : FragmentActivity() {

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
                            // The picker offers folders as a *backup* choice, so it reads the
                            // backup-scoped repository rather than the display-filtered one.
                            foldersRepository = container.backupAlbumRepository,
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

            // A locked gallery must not be readable from outside the app. `AppLockScreen` swaps the
            // content out of the tree, but that only covers what is *composed*: without FLAG_SECURE the
            // system still captures the last drawn frame — the photo grid — for the Recents thumbnail
            // when the activity stops, and the re-lock only happens on ON_START. The snapshot therefore
            // outlived the lock and was visible in the task switcher. Setting the flag blanks both the
            // Recents snapshot and any screenshot while the lock is on; it is cleared when the user
            // turns the lock off, so a user who wants to screenshot their own photos still can.
            val lockEnabled by appLockViewModel.enabled.collectAsStateWithLifecycle()
            LaunchedEffect(lockEnabled) {
                window.setFlags(
                    if (lockEnabled) WindowManager.LayoutParams.FLAG_SECURE else 0,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
            }

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
