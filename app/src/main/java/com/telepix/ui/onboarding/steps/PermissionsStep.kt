package com.telepix.ui.onboarding.steps

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.telepix.R
import com.telepix.permissions.MediaPermissionState
import com.telepix.ui.theme.TelepixTokens

/**
 * Screen 3 — Permissions. Explains why media access is needed and reflects the *real* current
 * state (never faked). The request/open-settings actions are driven by the host scaffold using
 * [com.telepix.permissions.rememberMediaPermissionState].
 */
@Composable
fun PermissionsStep(permissionState: MediaPermissionState, modifier: Modifier = Modifier) {
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
                text = stringResource(R.string.onboarding_permissions_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.onboarding_permissions_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.sm),
            )
        }

        if (permissionState != MediaPermissionState.NotRequested) {
            PermissionStateCard(permissionState)
        }
    }
}

@Composable
private fun PermissionStateCard(state: MediaPermissionState) {
    val spacing = TelepixTokens.spacing

    val icon: ImageVector
    val titleRes: Int
    val bodyRes: Int
    when (state) {
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (state == MediaPermissionState.PermanentlyDenied) {
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
    }
}
