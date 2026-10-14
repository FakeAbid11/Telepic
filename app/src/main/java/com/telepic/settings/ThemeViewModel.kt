package com.telepic.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepic.diagnostics.TelepicLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Holds the app-wide theme preference and exposes it as [StateFlow] for unidirectional
 * Compose state. Created once at the activity level so every screen shares one source.
 */
class ThemeViewModel(
    private val repository: SettingsRepository,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = repository.themeMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ThemeMode.Default,
        )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            try {
                repository.setThemeMode(mode)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // A failed preference write must not escape viewModelScope and take the app down; the
                // previously persisted theme simply stays in effect instead of being half-applied.
                TelepicLog.warn("ThemeViewModel", "could not persist the theme preference", throwable)
            }
        }
    }
}
