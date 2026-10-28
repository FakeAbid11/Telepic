package com.telepic.settings

import androidx.biometric.BiometricManager

/**
 * What the app-lock gate could establish about the device's screen-lock credential.
 *
 * Three states rather than a Boolean for the same reason [AppLockState] has four: "could not be
 * determined" is a real answer the gate has to handle, and collapsing it into "no credential" is
 * precisely how a lock opens without authenticating.
 */
internal enum class CredentialAvailability {
    /** A PIN, pattern or password is provably configured, so the gate can be enforced. */
    Available,

    /** The device provably has no credential — there is nothing to authenticate against. */
    NoCredential,

    /**
     * The platform could not answer. Transient: the biometric service can be busy, unavailable, or
     * waiting on a security update. Never read as [NoCredential] — the gate must stay closed and ask
     * the user to retry.
     */
    Unknown,
}

/**
 * Which mechanism the gate must use to authenticate.
 *
 * The split is not a preference; it is what `androidx.biometric` permits per API level. See
 * [appLockPromptMode] for the mapping and `AppLockScreen` for the builder calls.
 */
internal enum class AppLockPromptMode {
    /** One prompt offering the biometric, with the device credential as its built-in fallback. */
    BiometricOrCredential,

    /** One prompt offering the device credential only — legal on API 30+, where it is supported. */
    CredentialOnlyPrompt,

    /**
     * The platform's own credential screen, because **no credential-only `PromptInfo` exists below
     * API 30**: `DEVICE_CREDENTIAL` alone is an unsupported authenticator combination there, and
     * `setDeviceCredentialAllowed(true)` always also permits the biometric.
     */
    CredentialOnlySystemScreen,
}

/** What the gate must do when the user asks to unlock. */
internal enum class AppLockUnlockAction {
    /** Nothing to authenticate against, so release the gate. */
    Unlock,

    /** Raise the `BiometricPrompt`. */
    Prompt,

    /** Launch the platform credential screen (see [AppLockPromptMode.CredentialOnlySystemScreen]). */
    SystemCredentialScreen,

    /** The credential could not be verified. Keep the gate closed and offer a retry. */
    VerifyFailed,
}

/**
 * The lowest API level at which `DEVICE_CREDENTIAL` is a legal `setAllowedAuthenticators` value.
 *
 * Below it the library rejects the combination outright, which is why the two paths diverge here
 * rather than at some arbitrary version. (The combined `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` mask
 * has its own, narrower restriction — it is rejected on API 28 and 29 only — so it is never used
 * below 30 either.)
 */
private const val CREDENTIAL_PROMPT_MIN_SDK = 30

/**
 * Whether the device has a credential the gate can authenticate against.
 *
 * Below API 30 the **Keyguard answer decides and the biometric query is ignored**. That is not a
 * shortcut: `BiometricManager.canAuthenticate(DEVICE_CREDENTIAL)` returns
 * `BIOMETRIC_ERROR_UNSUPPORTED` there — it short-circuits on the unsupported combination *before* it
 * ever looks for a credential — so treating its answer as authoritative reported "no credential" on
 * every Android 8, 9 and 10 device, and the gate opened with no authentication at all.
 *
 * At API 30+ the query is the right instrument, because it is the same check the prompt itself will
 * make. Anything that is neither a clean success nor a definite "nothing enrolled" is [Unknown].
 */
internal fun credentialAvailability(
    sdkInt: Int,
    isDeviceSecure: Boolean,
    canAuthenticateDeviceCredential: Int,
): CredentialAvailability {
    if (sdkInt < CREDENTIAL_PROMPT_MIN_SDK) {
        return if (isDeviceSecure) CredentialAvailability.Available else CredentialAvailability.NoCredential
    }
    return when (canAuthenticateDeviceCredential) {
        BiometricManager.BIOMETRIC_SUCCESS -> CredentialAvailability.Available
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> CredentialAvailability.NoCredential
        // HW_UNAVAILABLE, SECURITY_UPDATE_REQUIRED, STATUS_UNKNOWN, NO_HARDWARE and anything the
        // platform adds later: not provably absent, so not a reason to open.
        else -> CredentialAvailability.Unknown
    }
}

/**
 * Which mechanism to use, from the API level and the user's fingerprint preference.
 *
 * The preference can only ever *add* the biometric. Turning it off still leaves the credential as
 * the way in — it never removes the lock, and it never removes the fallback.
 */
internal fun appLockPromptMode(sdkInt: Int, biometricUnlockEnabled: Boolean): AppLockPromptMode = when {
    biometricUnlockEnabled -> AppLockPromptMode.BiometricOrCredential
    sdkInt >= CREDENTIAL_PROMPT_MIN_SDK -> AppLockPromptMode.CredentialOnlyPrompt
    else -> AppLockPromptMode.CredentialOnlySystemScreen
}

/**
 * The decision table for an unlock request.
 *
 * The order is the whole point. A missing prompt host and a provably absent credential both release
 * the gate — the first cannot happen in production (`MainActivity` is a `FragmentActivity`) and
 * exists so a hostless embedding can never trap the user, the second because there is nothing to
 * check against. Everything else stays closed: an unverifiable credential is [VerifyFailed], and so
 * is a prompt that failed to build, because a `PromptInfo` we could not construct is not evidence
 * that the device is unlocked.
 */
internal fun appLockUnlockAction(
    mode: AppLockPromptMode,
    availability: CredentialAvailability,
    hasPromptHost: Boolean,
    hasPromptInfo: Boolean,
): AppLockUnlockAction = when {
    !hasPromptHost -> AppLockUnlockAction.Unlock
    availability == CredentialAvailability.NoCredential -> AppLockUnlockAction.Unlock
    availability == CredentialAvailability.Unknown -> AppLockUnlockAction.VerifyFailed
    mode == AppLockPromptMode.CredentialOnlySystemScreen -> AppLockUnlockAction.SystemCredentialScreen
    hasPromptInfo -> AppLockUnlockAction.Prompt
    else -> AppLockUnlockAction.VerifyFailed
}
