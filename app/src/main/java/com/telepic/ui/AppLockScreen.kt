package com.telepic.ui

import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.telepic.R
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
 * Authentication is delegated to the device's own credential (PIN / pattern / password) through
 * `BiometricPrompt` in `DEVICE_CREDENTIAL` mode. That is deliberate: Telepic stores no credential of
 * its own, so there is nothing for it to get wrong — the platform owns the secret, the rate limiting
 * and the lockout. This replaced the deprecated `KeyguardManager.createConfirmDeviceCredentialIntent`,
 * which still worked on every supported API but was flagged for removal.
 *
 * The prompt is raised immediately, because the only thing a lock screen is for is getting out of the
 * way once the user has authenticated. If they dismiss it, the button re-raises it.
 */
@Composable
fun AppLockScreen(
    onUnlocked: () -> Unit,
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

    val promptInfo = remember(context) {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.app_lock_prompt_title))
            .setSubtitle(context.getString(R.string.app_lock_prompt_body))
            // minSdk 26: DEVICE_CREDENTIAL is the supported authenticator. Do NOT also set a negative
            // button — it conflicts with DEVICE_CREDENTIAL and throws on API 30+.
            .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
    }

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
        // No credential to check against, or no FragmentActivity host to prompt from: releasing the
        // gate is the only honest outcome. Trapping the user behind a prompt the device will never
        // show would be the worst possible bug here.
        if (prompt == null || !context.hasDeviceCredential()) {
            onUnlocked()
        } else {
            prompt.authenticate(promptInfo)
        }
    }

    LaunchedEffect(Unit) { requestUnlock() }

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
                text = stringResource(R.string.app_lock_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = spacing.sm),
            )
            Button(
                onClick = requestUnlock,
                modifier = Modifier.padding(top = spacing.xl),
            ) {
                Text(stringResource(R.string.app_lock_unlock))
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

/** True when the device has a PIN / pattern / password to authenticate against. */
private fun Context.hasDeviceCredential(): Boolean =
    BiometricManager.from(this)
        .canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) ==
        BiometricManager.BIOMETRIC_SUCCESS

/** Walks the `ContextWrapper` chain to the hosting [FragmentActivity], or null when there is none. */
private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
