package com.telepix.settings

/**
 * The user's theme preference. Dark is the Telepix default (see [Default]).
 */
enum class ThemeMode {
    /** Follow the system-wide light/dark setting. */
    SYSTEM,

    /** Always use the light theme. */
    LIGHT,

    /** Always use the dark theme. */
    DARK,
    ;

    /**
     * Resolves this preference to a concrete dark/light decision.
     *
     * @param isSystemInDarkTheme the current system dark-mode state, used only for [SYSTEM].
     */
    fun isDarkTheme(isSystemInDarkTheme: Boolean): Boolean = when (this) {
        SYSTEM -> isSystemInDarkTheme
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** Telepix ships with dark as the default experience. */
        val Default: ThemeMode = DARK

        /**
         * Safe parse used when reading a persisted value; unknown/blank values fall back
         * to [Default] instead of throwing.
         */
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: Default
    }
}
