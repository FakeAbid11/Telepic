package com.telepix.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies the startup routing rule: incomplete → onboarding, complete → main application. */
class StartupDestinationTest {

    @Test
    fun `incomplete onboarding routes to onboarding`() {
        assertEquals(StartupDestination.ONBOARDING, StartupDestination.forCompletion(false))
    }

    @Test
    fun `completed onboarding routes to the main application`() {
        assertEquals(StartupDestination.MAIN, StartupDestination.forCompletion(true))
    }
}
