package com.telepic.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The authenticator combinations `androidx.biometric` actually accepts, pinned per API level.
 *
 * This is a characterisation test of a dependency, and it exists for one reason: the app shipped a
 * lock screen that built `PromptInfo` with `DEVICE_CREDENTIAL` on every API level, which **throws**
 * below API 30 — so opening the lock screen crashed on Android 8, 9 and 10. Nothing in the suite ran
 * below sdk 33, and `AppLockScreen` built its prompt during composition, so the crash was invisible
 * until a device tried it.
 *
 * The three tests at 28 and 29 are that regression. The rest guard the branches the screen now
 * selects between, so a future `androidx.biometric` bump that changes the matrix fails here instead
 * of on a user's phone.
 *
 * No `Context` and no resources: only the builder contract is under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppLockPromptInfoTest {

    private fun credentialOnly(): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Telepic")
            .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

    @Test
    @Config(sdk = [28])
    fun `a credential-only prompt is unsupported on api 28`() {
        // The combination the shipped build used unconditionally. `build()` throws, and because the
        // builder ran during composition the lock screen died on the first frame it was shown.
        val thrown = assertThrows(IllegalArgumentException::class.java) { credentialOnly() }
        assertTrue(
            "expected an unsupported-combination message, got '${thrown.message.orEmpty()}'",
            thrown.message.orEmpty().contains("unsupported", ignoreCase = true),
        )
    }

    @Test
    @Config(sdk = [29])
    fun `a credential-only prompt is unsupported on api 29`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) { credentialOnly() }
        assertTrue(
            "expected an unsupported-combination message, got '${thrown.message.orEmpty()}'",
            thrown.message.orEmpty().contains("unsupported", ignoreCase = true),
        )
    }

    @Test
    fun `a credential-only prompt builds on api 30 and above`() {
        // The branch `AppLockScreen` uses for API 30+ with the fingerprint turned off.
        assertEquals(
            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            credentialOnly().allowedAuthenticators,
        )
    }

    @Test
    fun `fingerprint plus credential builds on api 30 and above`() {
        // The branch used for API 30+ with the fingerprint turned on.
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Telepic")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        assertTrue(
            "the biometric and the credential must both be allowed",
            info.allowedAuthenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL != 0 &&
                info.allowedAuthenticators and BiometricManager.Authenticators.BIOMETRIC_STRONG != 0,
        )
    }

    @Test
    @Config(sdk = [28])
    @Suppress("DEPRECATION")
    fun `the deprecated setter builds the below-30 fingerprint prompt`() {
        // The branch used for API 26-29 with the fingerprint turned on. It is the only builder that
        // yields "fingerprint, with the credential as its fallback" below API 30, which is why the
        // screen uses a deprecated call there rather than a supported one that does not exist.
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Telepic")
            .setDeviceCredentialAllowed(true)
            .build()
        assertTrue(
            "the credential must remain allowed as the fallback",
            info.allowedAuthenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL != 0,
        )
    }

    @Test
    fun `a negative button is rejected when the credential is allowed`() {
        // Why no branch in `buildAppLockPromptInfo` sets one: every mode that allows the credential
        // would throw, and the screen would have no prompt at all.
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Telepic")
                .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .setNegativeButtonText("Cancel")
                .build()
        }
        assertTrue(
            "expected a negative-text message, got '${thrown.message.orEmpty()}'",
            thrown.message.orEmpty().contains("Negative text", ignoreCase = true),
        )
    }
}
