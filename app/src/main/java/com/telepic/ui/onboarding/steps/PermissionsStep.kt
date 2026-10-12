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
 * They are separate cards with separate actions rather than one combined prompt, so the optional
 * one can never ride on the required one — the media action stays the scaffold's primary CTA while
 * the notification ask is scoped to its own card, and the user can decline it and still continue.
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
            StateCard(
                icon = icon,
                titleRes = titleRes,
                bodyRes = bodyRes,
                isError = permissionState == MediaPermissionState.PermanentlyDenied,
            )
        }

        // Hidden entirely below API 33, where notifications need no runtime permission: a
        // "granted" card for a permission that does not exist would be noise, and offering the ask
        // would be a dead button.
        if (notificationState != NotificationPermissionState.NotRequired) {
            NotificationSection(
                state = notificationState,
                onRequest = onRequestNotifications,
            )
        }
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
) {
    val spacing = TelepicTokens.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = stringResource(R.string.onboarding_notifications_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.onboarding_notifications_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val granted = state == NotificationPermissionState.Granted
        StateCard(
            icon = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Info,
            titleRes = if (granted) {
                R.string.onboarding_notifications_granted
            } else {
                R.string.onboarding_notifications_denied
            },
            bodyRes = if (granted) {
                R.string.onboarding_notifications_granted_body
            } else {
                R.string.onboarding_notifications_denied_body
            },
            // Only Denied can still produce a dialog. Once granted there is nothing to ask for, so
            // the action disappears rather than sitting there as a no-op.
            action = if (granted) null else onRequest,
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
                    Text(stringResource(R.string.onboarding_notifications_allow))
                }
            }
        }
    }
}
