package com.telepix.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telepix.R
import com.telepix.onboarding.BackupPreference
import com.telepix.permissions.MediaPermissionState
import com.telepix.permissions.rememberMediaPermissionState
import com.telepix.telegram.TelegramAuthController
import com.telepix.ui.onboarding.components.OnboardingScaffold
import com.telepix.ui.onboarding.components.TAG_ONBOARDING_PRIMARY
import com.telepix.ui.onboarding.steps.BackupPreferencesStep
import com.telepix.ui.onboarding.steps.HowItWorksStep
import com.telepix.ui.onboarding.steps.PermissionsStep
import com.telepix.ui.onboarding.steps.ReadyStep
import com.telepix.ui.onboarding.steps.TelegramStep
import com.telepix.ui.onboarding.steps.WelcomeStep

/** Test tag for the final "Start using Telepix" action. */
const val TAG_ONBOARDING_START = "onboarding_start"

private const val TOTAL_STEPS = 6

/**
 * The six-screen first-run flow. Owns step navigation, back behavior, progress, and the
 * per-step primary/secondary actions. Persistent decisions (backup preference, completion)
 * live in the ViewModel; the transient step index survives configuration changes via
 * [rememberSaveable]. The main bottom navigation is intentionally not shown here.
 */
@Composable
fun OnboardingScreen(
    backupPreference: BackupPreference?,
    telegramController: TelegramAuthController,
    onBackupPreferenceChange: (BackupPreference) -> Unit,
    onComplete: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val permission = rememberMediaPermissionState()
    val telegramState by telegramController.state.collectAsStateWithLifecycle()

    // System back walks one step at a time; on the first step it falls through to the
    // normal Activity back behavior instead of trapping or exiting into a broken state.
    BackHandler(enabled = step > 0) { step -= 1 }

    val goToNext = remember { { if (step < TOTAL_STEPS - 1) step += 1 } }

    AnimatedContent(
        targetState = step,
        transitionSpec = {
            val forward = targetState > initialState
            val dir = if (forward) 1 else -1
            (
                slideInHorizontally(tween(300)) { width -> dir * width / 5 } +
                    fadeIn(tween(300))
                ) togetherWith (
                slideOutHorizontally(tween(300)) { width -> -dir * width / 5 } +
                    fadeOut(tween(200))
                )
        },
        label = "onboardingStep",
    ) { current ->
        val continueLabel = stringResource(R.string.onboarding_continue)

        var primaryLabel: String? = null
        var onPrimaryAction: (() -> Unit)? = null
        var primaryEnabled = true
        var secondaryLabel: String? = null
        var onSecondaryAction: (() -> Unit)? = null
        var primaryTestTag = TAG_ONBOARDING_PRIMARY

        when (current) {
            0, 1 -> {
                primaryLabel = continueLabel
                onPrimaryAction = goToNext
            }
            2 -> when (permission.state) {
                MediaPermissionState.NotRequested -> {
                    primaryLabel = stringResource(R.string.onboarding_permissions_grant)
                    onPrimaryAction = permission.requestPermission
                    secondaryLabel = continueLabel
                    onSecondaryAction = goToNext
                }
                MediaPermissionState.Denied -> {
                    primaryLabel = stringResource(R.string.onboarding_permissions_try_again)
                    onPrimaryAction = permission.requestPermission
                    secondaryLabel = continueLabel
                    onSecondaryAction = goToNext
                }
                MediaPermissionState.PermanentlyDenied -> {
                    primaryLabel = stringResource(R.string.onboarding_permissions_open_settings)
                    onPrimaryAction = permission.openAppSettings
                    secondaryLabel = continueLabel
                    onSecondaryAction = goToNext
                }
                MediaPermissionState.Granted,
                MediaPermissionState.Partial,
                -> {
                    primaryLabel = continueLabel
                    onPrimaryAction = goToNext
                }
            }
            3 -> {
                // TelegramStep owns its interactive controls (connect / phone / code / password /
                // continue). The scaffold only offers a non-blocking "Set up later" escape so the
                // flow can never trap the user.
                secondaryLabel = stringResource(R.string.onboarding_telegram_skip)
                onSecondaryAction = goToNext
            }
            4 -> {
                primaryLabel = continueLabel
                onPrimaryAction = goToNext
                // Only advance once a valid backup choice exists.
                primaryEnabled = backupPreference != null
            }
            5 -> {
                primaryLabel = stringResource(R.string.onboarding_ready_start)
                onPrimaryAction = onComplete
                primaryTestTag = TAG_ONBOARDING_START
            }
        }

        OnboardingScaffold(
            stepIndex = current,
            totalSteps = TOTAL_STEPS,
            progressLabel = stringResource(R.string.onboarding_progress, current + 1, TOTAL_STEPS),
            onBack = if (current > 0) {
                { step -= 1 }
            } else {
                null
            },
            primaryLabel = primaryLabel,
            onPrimaryAction = onPrimaryAction,
            primaryEnabled = primaryEnabled,
            primaryTestTag = primaryTestTag,
            secondaryLabel = secondaryLabel,
            onSecondaryAction = onSecondaryAction,
            content = {
                when (current) {
                    0 -> WelcomeStep()
                    1 -> HowItWorksStep()
                    2 -> PermissionsStep(permission.state)
                    3 -> TelegramStep(
                        controller = telegramController,
                        state = telegramState,
                        onContinue = goToNext,
                    )
                    4 -> BackupPreferencesStep(backupPreference, onBackupPreferenceChange)
                    5 -> ReadyStep(permission.state, telegramState, backupPreference)
                }
            },
        )
    }
}
