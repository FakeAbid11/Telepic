package com.telepix.ui.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.telepix.R
import com.telepix.telegram.TelegramAuthController
import com.telepix.telegram.TelegramAuthState
import com.telepix.ui.theme.TelepixTokens

/**
 * Screen 4 — Telegram Login (Phase 4, real).
 *
 * Renders the actual TDLib authentication state: connecting, phone number, confirmation code,
 * optional 2FA password, authorized account, or a recoverable error. It never fabricates a
 * successful login; if no backend is available it shows an honest unavailable state.
 */
@Composable
fun TelegramStep(
    controller: TelegramAuthController,
    state: TelegramAuthState,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TelepixTokens.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screenMargin, vertical = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Column {
            Text(
                text = stringResource(R.string.onboarding_telegram_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.onboarding_telegram_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }

        when {
            !controller.isBackendAvailable -> UnavailableCard()
            else -> AuthContent(controller, state, onContinue)
        }
    }
}

@Composable
private fun AuthContent(
    controller: TelegramAuthController,
    state: TelegramAuthState,
    onContinue: () -> Unit,
) {
    when (state) {
        TelegramAuthState.NotConnected, TelegramAuthState.Initializing -> {
            StatusRow(stringResource(R.string.telegram_connecting), busy = true)
            if (state == TelegramAuthState.NotConnected) {
                Button(onClick = controller::start, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_telegram_connect))
                }
            }
        }
        is TelegramAuthState.WaitingForPhoneNumber -> {
            var phone by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text(stringResource(R.string.telegram_phone_label)) },
                supportingText = { Text(state.error ?: stringResource(R.string.telegram_phone_hint)) },
                isError = state.error != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(
                label = stringResource(R.string.telegram_send_code),
                enabled = phone.isNotBlank(),
                onClick = { controller.submitPhoneNumber(phone) },
            )
        }
        is TelegramAuthState.WaitingForCode -> {
            var code by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text(stringResource(R.string.telegram_code_label)) },
                supportingText = { Text(state.error ?: stringResource(R.string.telegram_code_hint)) },
                isError = state.error != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(
                label = stringResource(R.string.telegram_verify),
                enabled = code.isNotBlank(),
                onClick = { controller.submitCode(code) },
            )
        }
        is TelegramAuthState.WaitingForPassword -> {
            var password by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.telegram_password_label)) },
                supportingText = { Text(state.error ?: stringResource(R.string.telegram_password_hint)) },
                isError = state.error != null,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(
                label = stringResource(R.string.telegram_verify),
                enabled = password.isNotBlank(),
                onClick = {
                    controller.submitPassword(password)
                    password = "" // clear the secret from UI state after submitting
                },
            )
        }
        is TelegramAuthState.WaitingForOtherDeviceConfirmation ->
            StatusRow(stringResource(R.string.telegram_other_device), busy = true)
        TelegramAuthState.WaitingForRegistration -> {
            // Telepix signs an existing Telegram account in as a storage layer; it does not create new
            // Telegram accounts. Be honest about that and offer sign-out rather than a silent dead-end.
            StatusRow(stringResource(R.string.telegram_registration), busy = false)
            TextButton(onClick = controller::logout, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.telegram_change_account))
            }
        }
        is TelegramAuthState.Authorized -> {
            StatusRow(
                stringResource(R.string.telegram_connected_as, state.user.displayName),
                busy = false,
            )
            PrimaryButton(label = stringResource(R.string.telegram_verify_continue), onClick = onContinue)
        }
        TelegramAuthState.LoggingOut -> StatusRow(stringResource(R.string.telegram_logging_out), busy = true)
        TelegramAuthState.Closing -> StatusRow(stringResource(R.string.telegram_connecting), busy = true)
        TelegramAuthState.Closed -> Button(onClick = controller::start, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_telegram_connect))
        }
        is TelegramAuthState.Failed -> {
            StatusRow(state.error.message, busy = false, error = true)
            PrimaryButton(label = stringResource(R.string.telegram_retry), onClick = controller::start)
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(label)
    }
}

@Composable
private fun StatusRow(text: String, busy: Boolean, error: Boolean = false) {
    val spacing = TelepixTokens.spacing
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun UnavailableCard() {
    val spacing = TelepixTokens.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Box(
                modifier = Modifier.size(56.dp).clip(CircleShape).padding(spacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp),
                )
            }
            Text(
                text = stringResource(R.string.onboarding_telegram_not_connected),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
