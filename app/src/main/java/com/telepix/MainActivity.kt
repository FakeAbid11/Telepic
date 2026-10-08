package com.telepix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.telepix.settings.ThemeViewModel
import com.telepix.ui.TelepixApp
import com.telepix.ui.theme.TelepixTheme

/**
 * Single-activity host. Resolves the persisted [com.telepix.settings.ThemeMode] and applies
 * the Telepix design system before rendering the app shell.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val container = (application as TelepixApplication).container
            val themeViewModel: ThemeViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { ThemeViewModel(container.settingsRepository) }
                },
            )
            val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
            TelepixTheme(darkTheme = themeMode.isDarkTheme(isSystemInDarkTheme())) {
                TelepixApp(
                    themeMode = themeMode,
                    onThemeModeChange = themeViewModel::setThemeMode,
                )
            }
        }
    }
}
