package com.telepic.ui.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.telepic.R
import com.telepic.permissions.MediaPermissionState
import com.telepic.permissions.NotificationPermissionState
import com.telepic.ui.theme.TelepicTokens

/** Test tag for the notification ask on the Permissions screen. */
const val TAG_PERMISSIONS_ALLOW_NOTIFICATIONS = "onboarding_allow_notifications"

/**
 * Screen 3 — Permissions. Explains why access is needed and reflects the *real* current state
 * (never faked).
 *
 * Two permissions are asked for here, and they are deliberately **not** the same thing:
 *  * media access is required for the app to do anything at all;
 *  * notification access is optional, and only ever drives the backup progress line.
 *
 * The screen makes that relationship explicit rather than leaving it to be inferred: the intro names
 * both asks, and each gets its own section headed by a `Required`/`Optional` badge, so the optional
 * one can never ride on the required one. They are separate cards with separate actions, the media
 * action stays the scaffold's primary CTA while the notification ask is scoped to its own card, and
 * the user can decline notifications and still continue.
 *
 * The media section also carries the rationale for `ACCESS_MEDIA_LOCATION`, which is folded into the
 * media request (API 29+) but has no visible prompt of its own — the only place the user is told why
 * Telepic reads the GPS already embedded in their photos, and that it never leaves the device.
 *
 * The media request/open-settings actions are driven by the host scaffold using
 * [com.telepic.permissions.rememberMediaPermissionState].
 */
@Composable
fun PermissionsStep(
    permissionState: MediaPermissionState,
    notificationState: NotificationPermissionState,
    onRequestNotifications: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenNotificationSettings: () -> Unit = {},
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
                text = stringResource(R.string.onboarding_permissions_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.onboarding_permissions_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.sm),
            )
        }

        if (permissionState != MediaPermissionState.NotRequested) {
            val icon: ImageVector
            val titleRes: Int
            val bodyRes: Int
            when (permissionState) {
                MediaPermissionState.Granted -> {
                    icon = Icons.Filled.CheckCircle
                    titleRes = R.string.onboarding_permissions_state_granted
                    bodyRes = R.string.onboarding_permissions_state_granted_body
                }
                MediaPermissionState.Partial -> {
                    icon = Icons.Filled.Info
                    titleRes = R.string.onboarding_permissions_state_partial
                    bodyRes = R.string.onboarding_permissions_state_partial_body
                }
                MediaPermissionState.Denied,
                MediaPermissionState.NotRequested,
                -> {
                    icon = Icons.Filled.Info
                    titleRes = R.string.onboarding_permissions_state_denied
                    bodyRes = R.string.onboarding_permissions_state_denied_body
                }
                MediaPermissionState.PermanentlyDenied -> {
                    icon = Icons.Filled.Lock
                    titleRes = R.string.onboarding_permissions_state_blocked
                    bodyRes = R.string.onboarding_permissions_state_blocked_body
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                SectionHeader(
                    titleRes = R.string.onboarding_permissions_media_section_title,
                    badgeRes = R.string.onboarding_permissions_required,
                    badgeEmphasized = true,
                )
                Text(
                    text = stringResource(R.string.onboarding_permissions_media_purpose),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StateCard(
                    icon = icon,
                    titleRes = titleRes,
                    bodyRes = bodyRes,
                    isError = permissionState == MediaPermissionState.PermanentlyDenied,
                )
                Text(
                    text = stringResource(R.string.onboarding_permissions_media_location_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Hidden entirely below API 33, where notifications need no runtime permission: a
        // "granted" card for a permission that does not exist would be noise, and offering the ask
        // would be a dead button.
        if (notificationState != NotificationPermissionState.NotRequired) {
            NotificationSection(
                state = notificationState,
                onRequest = onRequestNotifications,
                onOpenSettings = onOpenNotificationSettings,
            )
        }

        Text(
            text = stringResource(R.string.onboarding_permissions_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A section title with its `Required`/`Optional` badge. Shared by both permission sections so the
 * two read as the same kind of statement, and so the required/optional distinction is stated rather
 * than left for the user to guess from the copy.
 */
@Composable
private fun SectionHeader(titleRes: Int, badgeRes: Int, badgeEmphasized: Boolean) {
    val spacing = TelepicTokens.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        PermissionBadge(textRes = badgeRes, emphasized = badgeEmphasized)
    }
}

/** The `Required`/`Optional` pill: a small label on a container, emphasized for the required ask. */
@Composable
private fun PermissionBadge(textRes: Int, emphasized: Boolean) {
    val spacing = TelepicTokens.spacing
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (emphasized) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelMedium,
            color = if (emphasized) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xxs),
        )
    }
}

/**
 * The optional notification ask. Stated as what Telepic does with the permission — one progress
 * notification and nothing else — because that is the only reason it is being asked for.
 */
@Composable
private fun NotificationSection(
    state: NotificationPermissionState,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val spacing = TelepicTokens.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        SectionHeader(
            titleRes = R.string.onboarding_notifications_title,
            badgeRes = R.string.onboarding_permissions_optional,
            badgeEmphasized = false,
        )
        Text(
            text = stringResource(R.string.onboarding_notifications_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val granted = state == NotificationPermissionState.Granted
        val blocked = state == NotificationPermissionState.PermanentlyDenied
        StateCard(
            icon = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Info,
            titleRes = when {
                granted -> R.string.onboarding_notifications_granted
                blocked -> R.string.onboarding_notifications_blocked
                else -> R.string.onboarding_notifications_denied
            },
            bodyRes = when {
                granted -> R.string.onboarding_notifications_granted_body
                blocked -> R.string.onboarding_notifications_blocked_body
                else -> R.string.onboarding_notifications_denied_body
            },
            isError = blocked,
            // Once the platform will no longer show the dialog, "Allow notifications" is a button
            // that can never do anything — the only remaining path is system Settings.
            actionLabelRes = if (blocked) {
                R.string.onboarding_permissions_open_settings
            } else {
                R.string.onboarding_notifications_allow
            },
            action = when (state) {
                NotificationPermissionState.NotRequired,
                NotificationPermissionState.Granted,
                -> null
                NotificationPermissionState.PermanentlyDenied -> onOpenSettings
                NotificationPermissionState.Denied -> onRequest
            },
        )
    }
}

/**
 * A state card: icon, title, body, and an optional action. Shared by both permission sections so
 * the two read as the same kind of statement, and so an action lives inside the card it belongs to
 * rather than floating under an unrelated one.
 */
@Composable
private fun StateCard(
    icon: ImageVector,
    titleRes: Int,
    bodyRes: Int,
    isError: Boolean = false,
    actionLabelRes: Int = R.string.onboarding_notifications_allow,
    action: (() -> Unit)? = null,
) {
    val spacing = TelepicTokens.spacing
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(titleRes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(bodyRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.xxs),
                    )
                }
            }
            if (action != null) {
                TextButton(
                    onClick = action,
                    modifier = Modifier.testTag(TAG_PERMISSIONS_ALLOW_NOTIFICATIONS),
                ) {
                    Text(stringResource(actionLabelRes))
                }
            }
        }
    }
}
