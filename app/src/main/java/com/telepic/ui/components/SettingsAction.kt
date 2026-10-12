package com.telepic.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.telepic.R

/**
 * The Settings affordance: the gear that lives in the top-right corner of every top-level screen.
 *
 * Settings is deliberately **not** a bottom-bar tab. The tab bar is for the places you browse and
 * stay in (Photos, Cloud, Albums, Map); Settings is somewhere you go, change something, and leave.
 * Giving it a permanent quarter of the tab bar — and a highlighted state that meant "you are in
 * Settings" rather than "you are looking at your library" — misrepresented it.
 *
 * One shared control rather than four copies, so the gear is in the same place with the same
 * description on every tab, and the accessibility detail below cannot drift between them.
 *
 * The description lives on the [Icon] only. Setting it on the [IconButton] as well made TalkBack read
 * "Settings, Settings", because the merge policy for `ContentDescription` is `Concat`.
 */
@Composable
fun SettingsAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = stringResource(R.string.settings_action),
        )
    }
}
