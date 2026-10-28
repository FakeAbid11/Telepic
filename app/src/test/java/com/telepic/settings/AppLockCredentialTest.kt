package com.telepic.settings

import androidx.biometric.BiometricManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gate's decision table, pinned as pure logic.
 *
 * This is the part that cannot be reasoned about from a device test and cannot be reached by the
 * Robolectric suite (which always has a hostless, credential-less environment), so every branch that
 * decides *whether the library opens* is asserted here. Two of these cases are regressions rather
 * than hypotheticals — see the first test.
 */
class AppLockCredentialTest {

    // --- credentialAvailability -------------------------------------------------------------

    @Test
    fun `below api 30 the keyguard decides and the unsupported query is ignored`() {
        // The shipped bug: canAuthenticate(DEVICE_CREDENTIAL) returns BIOMETRIC_ERROR_UNSUPPORTED on
        // API 26-29 because the combination itself is unsupported there — it never looks for a
        // credential. Reading that as "no credential" opened the gate with no authentication at all
        // on every Android 8, 9 and 10 device.
        assertEquals(
            CredentialAvailability.Available,
            credentialAvailability(
                sdkInt = 29,
                isDeviceSecure = true,
                canAuthenticateDeviceCredential = BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            ),
        )
        assertEquals(
            CredentialAvailability.Available,
            credentialAvailability(
                sdkInt = 26,
                isDeviceSecure = true,
                canAuthenticateDeviceCredential = BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            ),
        )
    }

    @Test
    fun `below api 30 without a credential there is nothing to authenticate against`() {
        assertEquals(
            CredentialAvailability.NoCredential,
            credentialAvailability(
                sdkInt = 29,
                isDeviceSecure = false,
                canAuthenticateDeviceCredential = BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            ),
        )
    }

    @Test
    fun `api 30 reports a credential as available`() {
        // The keyguard value is deliberately not consulted here: the prompt asks the biometric
        // service, so the gate must ask the same thing.
        assertEquals(
            CredentialAvailability.Available,
            credentialAvailability(
                sdkInt = 30,
                isDeviceSecure = false,
                canAuthenticateDeviceCredential = BiometricManager.BIOMETRIC_SUCCESS,
            ),
        )
    }

    @Test
    fun `api 30 with nothing enrolled has no credential`() {
        assertEquals(
            CredentialAvailability.NoCredential,
            credentialAvailability(
                sdkInt = 30,
                isDeviceSecure = true,
                canAuthenticateDeviceCredential = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            ),
        )
    }

    @Test
    fun `an unanswerable query is unknown and never reads as no credential`() {
        listOf(
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            // A code the platform has not invented yet must not be guessed at either.
            12345,
        ).forEach { result ->
            assertEquals(
                "canAuthenticate=$result must not be read as a definite answer",
                CredentialAvailability.Unknown,
                credentialAvailability(
                    sdkInt = 33,
                    isDeviceSecure = true,
                    canAuthenticateDeviceCredential = result,
                ),
            )
        }
    }

    // --- appLockPromptMode ------------------------------------------------------------------

    @Test
    fun `the prompt mode follows the api level and the preference`() {
        assertEquals(
            AppLockPromptMode.BiometricOrCredential,
            appLockPromptMode(sdkInt = 29, biometricUnlockEnabled = true),
        )
        assertEquals(
            AppLockPromptMode.BiometricOrCredential,
            appLockPromptMode(sdkInt = 36, biometricUnlockEnabled = true),
        )
        // Credential-only is expressible as a PromptInfo only at 30+.
        assertEquals(
            AppLockPromptMode.CredentialOnlyPrompt,
            appLockPromptMode(sdkInt = 30, biometricUnlockEnabled = false),
        )
        assertEquals(
            AppLockPromptMode.CredentialOnlySystemScreen,
            appLockPromptMode(sdkInt = 29, biometricUnlockEnabled = false),
        )
    }

    // --- appLockUnlockAction ----------------------------------------------------------------

    @Test
    fun `a hostless gate releases rather than trapping the user`() {
        // Unreachable in production (MainActivity is a FragmentActivity); kept so an embedding
        // without one cannot lock the user out of their own library.
        assertEquals(
            AppLockUnlockAction.Unlock,
            appLockUnlockAction(
                mode = AppLockPromptMode.BiometricOrCredential,
                availability = CredentialAvailability.Available,
                hasPromptHost = false,
                hasPromptInfo = true,
            ),
        )
    }

    @Test
    fun `a provably absent credential releases the gate`() {
        assertEquals(
            AppLockUnlockAction.Unlock,
            appLockUnlockAction(
                mode = AppLockPromptMode.BiometricOrCredential,
                availability = CredentialAvailability.NoCredential,
                hasPromptHost = true,
                hasPromptInfo = true,
            ),
        )
    }

    @Test
    fun `an unverifiable credential never releases the gate`() {
        // The fail-open fix: a transient capability failure must not be indistinguishable from
        // "this device has no lock".
        assertEquals(
            AppLockUnlockAction.VerifyFailed,
            appLockUnlockAction(
                mode = AppLockPromptMode.BiometricOrCredential,
                availability = CredentialAvailability.Unknown,
                hasPromptHost = true,
                hasPromptInfo = true,
            ),
        )
        assertEquals(
            AppLockUnlockAction.VerifyFailed,
            appLockUnlockAction(
                mode = AppLockPromptMode.CredentialOnlySystemScreen,
                availability = CredentialAvailability.Unknown,
                hasPromptHost = true,
                hasPromptInfo = false,
            ),
        )
    }

    @Test
    fun `an available credential prompts`() {
        listOf(AppLockPromptMode.BiometricOrCredential, AppLockPromptMode.CredentialOnlyPrompt)
            .forEach { mode ->
                assertEquals(
                    "mode=$mode",
                    AppLockUnlockAction.Prompt,
                    appLockUnlockAction(
                        mode = mode,
                        availability = CredentialAvailability.Available,
                        hasPromptHost = true,
                        hasPromptInfo = true,
                    ),
                )
            }
    }

    @Test
    fun `the system credential screen is used only where no prompt exists`() {
        assertEquals(
            AppLockUnlockAction.SystemCredentialScreen,
            appLockUnlockAction(
                mode = AppLockPromptMode.CredentialOnlySystemScreen,
                availability = CredentialAvailability.Available,
                hasPromptHost = true,
                // Deliberately absent: this mode never builds a PromptInfo, which is exactly why it
                // must not fall through to VerifyFailed.
                hasPromptInfo = false,
            ),
        )
    }

    @Test
    fun `a prompt that failed to build does not fail open`() {
        assertEquals(
            AppLockUnlockAction.VerifyFailed,
            appLockUnlockAction(
                mode = AppLockPromptMode.BiometricOrCredential,
                availability = CredentialAvailability.Available,
                hasPromptHost = true,
                hasPromptInfo = false,
            ),
        )
    }
}
