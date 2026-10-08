package com.telepix.ui.onboarding.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.telepix.R
import com.telepix.ui.theme.TelepixTokens

/** Stable test tags for the onboarding action buttons. */
const val TAG_ONBOARDING_PRIMARY = "onboarding_primary"
const val TAG_ONBOARDING_SECONDARY = "onboarding_secondary"

/**
 * Shared frame for every onboarding step: a quiet progress header (with optional back), a
 * content slot, and a bottom action area. Centralizing this keeps all six screens consistent
 * and lets each step focus only on its own content.
 */
@Composable
fun OnboardingScaffold(
    stepIndex: Int,
    totalSteps: Int,
    progressLabel: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    primaryLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    primaryEnabled: Boolean = true,
    primaryTestTag: String = TAG_ONBOARDING_PRIMARY,
    secondaryLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    secondaryTestTag: String = TAG_ONBOARDING_SECONDARY,
    content: @Composable () -> Unit,
) {
    val spacing = TelepixTokens.spacing
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.xs, vertical = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.onboarding_back),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                OnboardingProgress(
                    stepIndex = stepIndex,
                    totalSteps = totalSteps,
                    progressLabel = progressLabel,
                    modifier = Modifier.weight(1f),
                )
                Box(modifier = Modifier.size(48.dp))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                content()
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screenMargin, vertical = spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                if (primaryLabel != null && onPrimaryAction != null) {
                    Button(
                        onClick = onPrimaryAction,
                        enabled = primaryEnabled,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag(primaryTestTag),
                    ) {
                        Text(primaryLabel)
                    }
                }
                if (secondaryLabel != null && onSecondaryAction != null) {
                    TextButton(
                        onClick = onSecondaryAction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(secondaryTestTag),
                    ) {
                        Text(secondaryLabel)
                    }
                }
            }
        }
    }
}
