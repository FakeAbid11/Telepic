package com.telepic.ui.onboarding.steps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.TelephonyManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.telepic.R
import com.telepic.telegram.CountryDialCode
import com.telepic.telegram.PhoneCountries
import com.telepic.telegram.TelegramAuthController
import com.telepic.telegram.TelegramAuthState
import com.telepic.telegram.normalizeE164
import com.telepic.ui.theme.TelepicTokens
import java.util.Locale

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
    val spacing = TelepicTokens.spacing
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
                modifier = Modifier.semantics { heading() },
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

@OptIn(ExperimentalMaterial3Api::class)
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
            val spacing = TelepicTokens.spacing
            var phone by rememberSaveable { mutableStateOf("") }
            val defaultCountry = rememberDefaultCountry()
            var countryIso by rememberSaveable { mutableStateOf(defaultCountry.iso) }
            val country = PhoneCountries.byIso(countryIso) ?: defaultCountry
            var menuExpanded by remember { mutableStateOf(false) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ExposedDropdownMenuBox(
                    expanded = menuExpanded,
                    onExpandedChange = { menuExpanded = it },
                    modifier = Modifier.width(132.dp),
                ) {
                    OutlinedTextField(
                        value = "${country.flag} ${country.dialCode}",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.telegram_country_label)) },
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        PhoneCountries.All.forEach { entry ->
                            DropdownMenuItem(
                                text = { Text("${entry.flag}  ${entry.name}  (${entry.dialCode})") },
                                onClick = {
                                    countryIso = entry.iso
                                    menuExpanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(stringResource(R.string.telegram_phone_label)) },
                    supportingText = { Text(state.error ?: stringResource(R.string.telegram_phone_hint)) },
                    isError = state.error != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.weight(1f),
                )
            }
            PrimaryButton(
                label = stringResource(R.string.telegram_send_code),
                enabled = phone.isNotBlank(),
                onClick = { controller.submitPhoneNumber(normalizeE164(phone, country.dialCode)) },
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
            // Deliberately NOT rememberSaveable: saved instance state is persisted to disk on
            // process death, which would leave the 2FA password behind long after the in-memory
            // clear below. Phone/code stay saveable — they are identity, not a standing secret.
            var password by remember { mutableStateOf("") }
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
        is TelegramAuthState.WaitingForOtherDeviceConfirmation -> {
            StatusRow(stringResource(R.string.telegram_other_device), busy = true)
            // TDLib hands us the confirmation link precisely so it can be opened where the account
            // already lives. Discarding it left anyone whose Telegram sits on another device with no
            // way to approve the login and no exit from this state at all, so surface the link — and
            // always pair it with a way out, so the state can never become a dead end.
            val context = LocalContext.current
            PrimaryButton(
                label = stringResource(R.string.telegram_open_link),
                onClick = { context.openTelegramLink(state.link) },
            )
            TextButton(onClick = controller::logout, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.telegram_change_account))
            }
        }
        TelegramAuthState.WaitingForRegistration -> {
            // Telepic signs an existing Telegram account in as a storage layer; it does not create new
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

/**
 * Best-effort preselect for the dial-code dropdown: the SIM's country, else the system locale's
 * country, else the United States. Both lookups need no runtime permission.
 */
@Composable
private fun rememberDefaultCountry(): CountryDialCode {
    val context = LocalContext.current
    return remember(context) {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        PhoneCountries.byIso(telephony?.simCountryIso)
            ?: PhoneCountries.byIso(Locale.getDefault().country)
            ?: PhoneCountries.Default
    }
}

/**
 * Hands the TDLib confirmation link to the Telegram app. A `tg://` link has no handler when
 * Telegram is not installed, and a missing handler surfaces as ActivityNotFoundException, so the
 * launch stays best-effort — the "use a different account" action beside it is the guaranteed exit.
 */
private fun Context.openTelegramLink(link: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
}

@Composable
private fun StatusRow(text: String, busy: Boolean, error: Boolean = false) {
    val spacing = TelepicTokens.spacing
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
    val spacing = TelepicTokens.spacing
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
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(spacing.sm),
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
            Text(
                text = stringResource(R.string.onboarding_telegram_not_connected_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
