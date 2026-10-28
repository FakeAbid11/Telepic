package com.telepic.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.telepic.R
import com.telepic.diagnostics.TelepicLog
import com.telepic.settings.AppLockPromptMode
import com.telepic.settings.AppLockUnlockAction
import com.telepic.settings.CredentialAvailability
import com.telepic.settings.appLockPromptMode
import com.telepic.settings.appLockUnlockAction
import com.telepic.settings.credentialAvailability
import com.telepic.ui.theme.TelepicTokens

/**
 * The app-lock gate. Shown *instead of* the app content, never over it, so nothing is drawn behind
 * the lock.
 *
 * What this screen does **not** do on its own is stop the system from capturing the app's last drawn
 * frame. That is `FLAG_SECURE`'s job, and it is applied in `MainActivity` while the lock preference
 * is on — see the comment there. Removing it would leave the unlocked photo grid visible in the
 * Recents thumbnail and capturable by a screenshot or screen recorder, because the re-lock happens on
 * `ON_START` and the snapshot is taken when the activity stops.
 *
 * **Authentication is always the device's own secret.** Telepic stores no credential, so there is
 * nothing for it to get wrong — the platform owns the secret, the rate limiting and the lockout. The
 * fingerprint is an *addition* on top of that, never a replacement: every path below still ends at
 * the PIN/pattern/password, so turning the biometric on cannot make the gate easier to pass.
 *
 * Three mechanisms, and the split is dictated by what `androidx.biometric` permits per API level —
 * not by preference:
 * - **API 30+**: one prompt allowing `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, which shows the
 *   fingerprint and offers the credential as its own fallback.
 * - **API 26–29 with the biometric on**: `setDeviceCredentialAllowed(true)`. `DEVICE_CREDENTIAL`
 *   alone is an *unsupported combination* below API 30 — building a prompt with it throws — and this
 *   deprecated setter is the only builder that yields "fingerprint, with the PIN as its fallback" in
 *   one prompt. It resolves to `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`, which on these versions means
 *   the fingerprint sensor.
 * - **API 26–29 with the biometric off**: `KeyguardManager.createConfirmDeviceCredentialIntent`.
 *   There is no credential-only `PromptInfo` below API 30 — none — so honouring "PIN only" requires
 *   the platform's own credential screen. That is the same call `androidx.biometric` itself makes on
 *   these versions when no biometric is enrolled, so this adds no platform surface the shipped stack
 *   did not already reach.
 *
 * The prompt is raised immediately, because the only thing a lock screen is for is getting out of
 * the way once the user has authenticated. If they dismiss it, the button re-raises it.
 */
@Composable
fun AppLockScreen(
    onUnlocked: () -> Unit,
    /**
     * Whether the biometric may be offered. Required rather than defaulted so every call site has to
     * state the policy it is applying; the gate passes the user's preference.
     */
    biometricUnlockEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val spacing = TelepicTokens.spacing

    // BiometricPrompt requires a FragmentActivity host; MainActivity is one. Resolved once. A null
    // host means there is no way to raise the prompt, which requestUnlock handles by releasing the
    // gate rather than trapping the user.
    val activity = remember(context) { context.findFragmentActivity() }
    val currentOnUnlocked by rememberUpdatedState(onUnlocked)
    val prompt = remember(activity, context) {
        activity?.let { host ->
            BiometricPrompt(
                host,
                ContextCompat.getMainExecutor(context),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        currentOnUnlocked()
                    }

                    override fun onAuthenticationFailed() {
                        // A single unrecognised attempt: the prompt stays up so the user can retry.
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        // Cancel / lockout / negative button: never unlock. The screen stays, and the
                        // button re-raises the prompt — the same behaviour as dismissing the old
                        // KeyguardManager confirmation.
                    }
                },
            )
        }
    }

    // Which mechanism this device can use. Recomputed only when the policy changes; the *capability*
    // is re-probed on every unlock attempt instead, because it can change while the app is open.
    val mode = remember(biometricUnlockEnabled) {
        appLockPromptMode(Build.VERSION.SDK_INT, biometricUnlockEnabled)
    }
    // Null in the credential-only-system-screen mode, which has no PromptInfo by construction, and
    // null if the builder rejects the combination — composition must never throw.
    val promptInfo = remember(context, mode) { context.buildAppLockPromptInfo(mode) }

    // The platform credential screen reports back through the activity result pipeline.
    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Only an explicit RESULT_OK is authentication. Cancelling leaves the gate closed.
        if (result.resultCode == Activity.RESULT_OK) currentOnUnlocked()
    }

    // Set when the credential could not be verified at all. The gate stays closed; the copy changes
    // and the button re-probes.
    var verifyFailed by remember { mutableStateOf(false) }

    // The prompt is cancelled when the screen leaves the tree.
    //
    // It is a platform object registered against the activity, so it outlives the composition: the
    // callback stayed registered after the screen was gone and could fire into a disposed
    // composition. Cancelling it on the way out means the system sheet goes away with the screen
    // instead of floating over whatever replaced it.
    DisposableEffect(prompt) {
        onDispose { runCatching { prompt?.cancelAuthentication() } }
    }

    val requestUnlock: () -> Unit = {
        verifyFailed = false
        when (
            appLockUnlockAction(
                mode = mode,
                // Probed here, not during composition, and re-probed on every attempt so a transient
                // failure — or a credential the user has just set — resolves on the next tap.
                availability = context.deviceCredentialAvailability(),
                hasPromptHost = prompt != null,
                hasPromptInfo = promptInfo != null,
            )
        ) {
            AppLockUnlockAction.Unlock -> onUnlocked()
            AppLockUnlockAction.VerifyFailed -> verifyFailed = true
            AppLockUnlockAction.Prompt -> {
                val host = prompt
                val info = promptInfo
                // Both are non-null whenever the action is Prompt; the check is kept so a future
                // edit cannot turn this branch into a crash.
                if (host != null && info != null) host.authenticate(info) else verifyFailed = true
            }
            AppLockUnlockAction.SystemCredentialScreen -> {
                val intent = context.confirmDeviceCredentialIntent()
                if (intent == null) {
                    // Provably no credential — the same conclusion the platform screen would reach.
                    onUnlocked()
                } else {
                    // A stripped OEM build can have no activity to handle this, and the failure is
                    // thrown synchronously. This is the one path that exists to be safe.
                    runCatching { credentialLauncher.launch(intent) }
                        .onFailure { throwable ->
                            TelepicLog.warn("AppLockScreen", "the credential screen could not be opened", throwable)
                            verifyFailed = true
                        }
                }
            }
        }
    }

    LaunchedEffect(Unit) { requestUnlock() }

    val bodyText = when {
        verifyFailed -> stringResource(R.string.app_lock_verify_failed_body)
        biometricUnlockEnabled -> stringResource(R.string.app_lock_body_biometric)
        else -> stringResource(R.string.app_lock_body)
    }
    val buttonText = if (verifyFailed) {
        stringResource(R.string.app_lock_verify_failed_retry)
    } else {
        stringResource(R.string.app_lock_unlock)
    }

    Surface(
        // This screen is rendered outside TelepicApp's Scaffold, so nothing else hands it the system
        // bar insets that `enableEdgeToEdge` makes the app's responsibility: without this the lock icon
        // and title can sit under the status bar on a short or landscape display.
        modifier = modifier.fillMaxSize().safeDrawingPadding(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            // The locked-out user's first screen, and until now it said nothing about whose app they
            // are proving they own. The name is an overline rather than a headline so the prompt — the
            // thing that actually needs to be legible — stays the loudest element.
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = spacing.md),
            )
            Text(
                text = stringResource(R.string.app_lock_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = spacing.lg),
            )
            Text(
                text = bodyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = spacing.sm),
            )
            Button(
                onClick = requestUnlock,
                modifier = Modifier.padding(top = spacing.xl),
            ) {
                Text(buttonText)
            }
        }
    }
}

/**
 * Shown when the app-lock preference could not be read at all, so whether the lock is on is unknown.
 *
 * This is the fail-*closed* counterpart to [AppLockScreen]: rather than assuming the lock is off (and
 * exposing the library), Telepic keeps the content hidden and offers an explicit retry. It is not an
 * authentication prompt — there is nothing to authenticate against when the state itself is unreadable
 * — so it never claims to have checked a credential.
 */
@Composable
fun AppLockRecoveryScreen(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TelepicTokens.spacing
    Surface(
        modifier = modifier.fillMaxSize().safeDrawingPadding(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.ReportProblem,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = spacing.md),
            )
            Text(
                text = stringResource(R.string.app_lock_recovery_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = spacing.lg),
            )
            Text(
                text = stringResource(R.string.app_lock_recovery_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = spacing.sm),
            )
            Button(
                onClick = onRetry,
                modifier = Modifier.padding(top = spacing.xl),
            ) {
                Text(stringResource(R.string.app_lock_recovery_retry))
            }
        }
    }
}

/**
 * Build the prompt for [mode], or null when the platform has none (or rejects the combination).
 *
 * Null is a first-class outcome, not a failure to hide: the caller turns it into the honest
 * "couldn't verify" state, which keeps the gate closed. Throwing here instead would crash the lock
 * screen — which is exactly what the previous `DEVICE_CREDENTIAL`-only builder did on API 26–29.
 *
 * No branch sets a negative button. A credential-allowing combination rejects one outright
 * (`build()` throws "Negative text must not be set…"), and below API 30 the library supplies its own
 * "Use PIN/pattern/password" action.
 */
@Suppress("DEPRECATION") // setDeviceCredentialAllowed: see the API 26-29 branch comment.
private fun Context.buildAppLockPromptInfo(mode: AppLockPromptMode): BiometricPrompt.PromptInfo? {
    val builder = BiometricPrompt.PromptInfo.Builder()
        .setTitle(getString(R.string.app_lock_prompt_title))
    return runCatching {
        when (mode) {
            AppLockPromptMode.BiometricOrCredential -> builder
                .setSubtitle(getString(R.string.app_lock_prompt_body_biometric))
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setAllowedAuthenticators(
                            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                        )
                    } else {
                        // Below API 30, DEVICE_CREDENTIAL is an unsupported combination on its own,
                        // and STRONG|CREDENTIAL is rejected on API 28-29. This deprecated setter
                        // (-> WEAK|CREDENTIAL) is the only way to get "fingerprint, with the PIN as
                        // its fallback" in a single prompt on those versions.
                        setDeviceCredentialAllowed(true)
                    }
                }
                .build()

            AppLockPromptMode.CredentialOnlyPrompt -> builder
                .setSubtitle(getString(R.string.app_lock_prompt_body))
                .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()

            // No credential-only PromptInfo exists below API 30; the caller uses the system screen.
            AppLockPromptMode.CredentialOnlySystemScreen -> null
        }
    }.onFailure { throwable ->
        TelepicLog.warn("AppLockScreen", "the app-lock prompt could not be built for $mode", throwable)
    }.getOrNull()
}

/**
 * What the device can prove about its screen-lock credential.
 *
 * Below API 30 the biometric query is deliberately **not** made: `canAuthenticate(DEVICE_CREDENTIAL)`
 * answers `BIOMETRIC_ERROR_UNSUPPORTED` there because the combination itself is unsupported, before
 * it ever looks for a credential. Reading that as "no credential" opened the gate with no
 * authentication at all on Android 8, 9 and 10, so on those versions the Keyguard answer is the
 * authority — which is also exactly what `androidx.biometric` uses internally for them.
 */
private fun Context.deviceCredentialAvailability(): CredentialAvailability = credentialAvailability(
    sdkInt = Build.VERSION.SDK_INT,
    isDeviceSecure = getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true,
    canAuthenticateDeviceCredential = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    } else {
        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
    },
)

/**
 * The platform's own credential screen, for the one case with no `PromptInfo`: API 26–29 with the
 * fingerprint turned off.
 *
 * Deprecated in API 29, and used here on purpose — see the [AppLockScreen] KDoc. The flags mirror
 * what `androidx.biometric` sets on the same intent, so the credential screen cannot be restored
 * later or reached from Recents. Returns null when the device has no credential, which the caller
 * treats as "nothing to authenticate against".
 */
@Suppress("DEPRECATION") // Deprecated in API 29; below API 30 it is the only credential-only screen.
private fun Context.confirmDeviceCredentialIntent(): Intent? =
    getSystemService(KeyguardManager::class.java)
        ?.createConfirmDeviceCredentialIntent(
            getString(R.string.app_lock_prompt_title),
            getString(R.string.app_lock_prompt_body),
        )
        ?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }

/** Walks the `ContextWrapper` chain to the hosting [FragmentActivity], or null when there is none. */
private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
