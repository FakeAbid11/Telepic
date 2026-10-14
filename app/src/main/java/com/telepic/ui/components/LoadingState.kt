package com.telepic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.telepic.R
import com.telepic.ui.theme.TelepicTokens

/**
 * Reusable Material 3 loading state so individual screens don't invent their own spinners.
 *
 * The label is the single announcement. The indicator used to carry [message] as its own
 * `contentDescription` *and* have a sibling `Text` rendering the same words, so a screen reader read
 * the loading state twice; it is decorative now, and the label below always renders (falling back to
 * a generic "loading" when no message is given) so there is still something to announce.
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    val spacing = TelepicTokens.spacing
    val fallbackDescription = stringResource(R.string.common_loading)
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(
                text = message ?: fallbackDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
