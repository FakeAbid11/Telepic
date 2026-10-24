package com.telepic.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import com.telepic.ui.theme.LocalReduceMotion
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telepic.R
import com.telepic.onboarding.BackupPreference
import com.telepic.permissions.MediaPermissionState
import com.telepic.permissions.rememberMediaPermissionState
import com.telepic.permissions.rememberNotificationPermissionState
import com.telepic.telegram.TelegramAuthController
import com.telepic.telegram.TelegramAuthState
import com.telepic.ui.onboarding.components.OnboardingScaffold
import com.telepic.ui.onboarding.components.TAG_ONBOARDING_PRIMARY
import com.telepic.ui.onboarding.steps.BackupPreferencesStep
import com.telepic.ui.onboarding.steps.HowItWorksStep
import com.telepic.ui.onboarding.steps.PermissionsStep
import com.telepic.ui.onboarding.steps.ReadyStep
import com.telepic.ui.onboarding.steps.TelegramStep
import com.telepic.ui.onboarding.steps.WelcomeStep

/** Test tag for the final "Start using Telepic" action. */
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
    backupBucketIds: Set<Long>,
    folders: List<com.telepic.domain.media.Album>,
    telegramController: TelegramAuthController,
    onBackupPreferenceChange: (BackupPreference) -> Unit,
    onNeedFolders: () -> Unit,
    onPickFolders: (Set<Long>) -> Unit,
    onComplete: () -> Unit,
    foldersLoaded: Boolean = true,
    /** The folder read failed, so the picker can say so and retry instead of claiming "no folders". */
    foldersFailed: Boolean = false,
    backupSetupFailed: Boolean = false,
    /**
     * The completion write failed — a different problem from a failed backup choice, and it needs
     * different words on the last step.
     */
    completionFailed: Boolean = false,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val permission = rememberMediaPermissionState()
    val notifications = rememberNotificationPermissionState()
    val telegramState by telegramController.state.collectAsStateWithLifecycle()

    // System back walks one step at a time; on the first step it falls through to the
    // normal Activity back behavior instead of trapping or exiting into a broken state.
    BackHandler(enabled = step > 0) { step -= 1 }

    val goToNext = remember { { if (step < TOTAL_STEPS - 1) step += 1 } }

    // Holds each step's own `rememberSaveable` state across step changes — see the note at the
    // `SaveableStateProvider` below.
    val saveableStateHolder = rememberSaveableStateHolder()

    val continueLabel = stringResource(R.string.onboarding_continue)

    // The chrome is resolved from `step` — the destination — so the progress header and the action
    // row are correct the instant the user acts, while only the page body animates (see below).
    var primaryLabel: String? = null
    var onPrimaryAction: (() -> Unit)? = null
    var primaryEnabled = true
    var primaryDisabledHint: String? = null
    var secondaryLabel: String? = null
    var onSecondaryAction: (() -> Unit)? = null
    var primaryTestTag = TAG_ONBOARDING_PRIMARY

    when (step) {
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
            //
            // The escape is withdrawn once the account *is* connected: the step's own "Continue"
            // button is the way forward then, and offering "Set up later" beside it would be two
            // contradicting affordances for a step that is already finished.
            if (telegramState !is TelegramAuthState.Authorized) {
                secondaryLabel = stringResource(R.string.onboarding_telegram_skip)
                onSecondaryAction = goToNext
            }
        }
        4 -> {
            primaryLabel = continueLabel
            onPrimaryAction = goToNext
            // Only advance once a valid backup choice exists.
            primaryEnabled = backupPreference != null
            // Cancelling the folder picker leaves no preference and no visible explanation, which
            // reads as a broken button rather than as a choice still to be made.
            if (backupPreference == null) {
                primaryDisabledHint = stringResource(R.string.onboarding_backup_choose_hint)
            }
        }
        5 -> {
            primaryLabel = stringResource(R.string.onboarding_ready_start)
            onPrimaryAction = onComplete
            primaryTestTag = TAG_ONBOARDING_START
        }
    }

    OnboardingScaffold(
        stepIndex = step,
        totalSteps = TOTAL_STEPS,
        progressLabel = stringResource(R.string.onboarding_progress, step + 1, TOTAL_STEPS),
        onBack = if (step > 0) {
            { step -= 1 }
        } else {
            null
        },
        primaryLabel = primaryLabel,
        onPrimaryAction = onPrimaryAction,
        primaryEnabled = primaryEnabled,
        primaryDisabledHint = primaryDisabledHint,
        primaryTestTag = primaryTestTag,
        secondaryLabel = secondaryLabel,
        onSecondaryAction = onSecondaryAction,
        content = {
            // Only the page body animates. Keeping the scaffold outside the transition leaves the
            // progress header and the action row anchored while the step changes, instead of the
            // entire frame sliding out from under the finger that just tapped it. fillMaxSize also
            // pins the body region, so AnimatedContent never animates its own height between steps.
            val reduceMotion = LocalReduceMotion.current
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    if (reduceMotion) {
                        // No lateral motion when the user asked for less — just a quick fade so the
                        // content change is still perceptible without vestibular load.
                        fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                    } else {
                        val forward = targetState > initialState
                        val dir = if (forward) 1 else -1
                        (
                            slideInHorizontally(tween(300)) { width -> dir * width / 5 } +
                                fadeIn(tween(300))
                            ) togetherWith (
                            slideOutHorizontally(tween(300)) { width -> -dir * width / 5 } +
                                fadeOut(tween(200))
                            )
                    }
                },
                label = "onboardingStep",
                modifier = Modifier.fillMaxSize(),
            ) { current ->
                // Each step's own `rememberSaveable` state is kept in a holder keyed by the step.
                //
                // Without it the state is dropped the moment the step leaves the composition: a
                // `SaveableStateHolder` only survives a *registry save* (a rotation or process
                // death), not a step change. So a user who typed their phone number, tapped "Set up
                // later" and came back found the field empty — while the TDLib controller was still
                // waiting for that very number.
                saveableStateHolder.SaveableStateProvider(current) {
                    when (current) {
                        0 -> WelcomeStep()
                        1 -> HowItWorksStep()
                        2 -> PermissionsStep(
                            permissionState = permission.state,
                            notificationState = notifications.state,
                            onRequestNotifications = notifications.request,
                            // Once the platform stops showing the dialog, Settings is the only path.
                            onOpenNotificationSettings = notifications.openSettings,
                        )
                        3 -> TelegramStep(
                            controller = telegramController,
                            state = telegramState,
                            onContinue = goToNext,
                        )
                        4 -> BackupPreferencesStep(
                            selected = backupPreference,
                            selectedBucketIds = backupBucketIds,
                            folders = folders,
                            foldersLoaded = foldersLoaded,
                            foldersFailed = foldersFailed,
                            setupFailed = backupSetupFailed,
                            // Notification access is asked for on the Permissions step, one screen
                            // earlier. It is deliberately NOT re-asked here: prompting again inside the
                            // same flow is the double-prompt anti-pattern, and a second denial in a row
                            // is what makes Android stop showing the dialog at all.
                            onSelect = onBackupPreferenceChange,
                            onNeedFolders = onNeedFolders,
                            onPickFolders = onPickFolders,
                        )
                        5 -> ReadyStep(
                            permissionState = permission.state,
                            telegramState = telegramState,
                            backupPreference = backupPreference,
                            // The completion write can fail too, and this is the screen whose button it is.
                            setupFailed = backupSetupFailed,
                            completionFailed = completionFailed,
                        )
                    }
                }
            }
        },
    )
}
