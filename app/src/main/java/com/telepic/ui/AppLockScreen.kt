package com.telepic.ui

import android.app.Activity
import android.app.KeyguardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.telepic.R
import com.telepic.ui.theme.TelepicTokens

/**
 * The app-lock gate. Shown *instead of* the app content, never over it, so nothing is drawn behind
 * the lock and nothing leaks into a screenshot or the recents thumbnail.
 *
 * Authentication is delegated to the device's own credential (PIN / pattern / password) through
 * `KeyguardManager`. That is deliberate: Telepic stores no credential of its own, so there is nothing
 * for it to get wrong — the platform owns the secret, the rate limiting and the lockout.
 *
 * The prompt is raised immediately, because the only thing a lock screen is for is getting out of the
 * way once the user has authenticated. If they dismiss it, the button re-raises it.
 */
@Suppress("DEPRECATION")
@Composable
fun AppLockScreen(
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val spacing = TelepicTokens.spacing

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) onUnlocked()
    }

    // `createConfirmDeviceCredentialIntent` is deprecated in favour of BiometricPrompt, which would
    // require a FragmentActivity host and an extra dependency for the same device-credential prompt.
    // It still works on every API Telepic supports (26+) and returns null when the device has no
    // credential — the case the caller has already excluded, and which is handled below anyway.
    val requestUnlock: () -> Unit = {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val intent = keyguard?.createConfirmDeviceCredentialIntent(
            context.getString(R.string.app_lock_prompt_title),
            context.getString(R.string.app_lock_prompt_body),
        )
        if (intent != null) {
            launcher.launch(intent)
        } else {
            // No credential to check against: releasing the gate is the only honest outcome. Trapping
            // the user behind a prompt the device will never show would be the worst possible bug here.
            onUnlocked()
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
