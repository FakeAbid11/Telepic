package com.telepix.ui.onboarding.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.telepix.R
import com.telepix.ui.onboarding.components.OnboardingIllustration
import com.telepix.ui.theme.TelepixTokens

/**
 * Screen 1 — Welcome. Branding, a concise value proposition, and a media-to-cloud visual.
 * Deliberately free of technical detail.
 */
@Composable
fun WelcomeStep(modifier: Modifier = Modifier) {
    val spacing = TelepixTokens.spacing
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.xl, vertical = spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                OnboardingIllustration(
                    icon = Icons.Filled.PhotoLibrary,
                    contentDescription = stringResource(R.string.onboarding_welcome_illustration),
                    size = 184.dp,
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.background)
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    OnboardingIllustration(
                        icon = Icons.Filled.Cloud,
                        contentDescription = null,
                        size = 60.dp,
                    )
                }
            }

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = spacing.xxl),
            )
            Text(
                text = stringResource(R.string.onboarding_welcome_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = spacing.sm),
            )
        }
    }
}
