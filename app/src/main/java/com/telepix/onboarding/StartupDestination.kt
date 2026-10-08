package com.telepix.onboarding

/**
 * Where the app should route on launch. Kept as a tiny pure model so the startup routing rule
 * is unit-testable independently of Compose/Activity code.
 */
enum class StartupDestination {
    ONBOARDING,
    MAIN,
    ;

    companion object {
        /** Incomplete onboarding routes to onboarding; otherwise to the main application. */
        fun forCompletion(onboardingCompleted: Boolean): StartupDestination =
            if (onboardingCompleted) MAIN else ONBOARDING
    }
}
