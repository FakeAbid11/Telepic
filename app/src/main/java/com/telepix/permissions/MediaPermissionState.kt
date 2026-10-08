package com.telepix.permissions

/**
 * The media-access states the onboarding Permissions screen distinguishes.
 *
 * Derived from Android's real permission APIs on every composition — never persisted, since
 * the OS is the authoritative source.
 */
enum class MediaPermissionState {
    /** The user has not been asked yet. */
    NotRequested,

    /** Full photo/video access is available. */
    Granted,

    /** Limited access to user-selected photos/videos (Android 14+). */
    Partial,

    /** Denied, but the system dialog can be shown again. */
    Denied,

    /** Denied and "don't ask again"; recovery requires app Settings. */
    PermanentlyDenied,
    ;

    /** Whether the app can read at least some media. */
    val hasAccess: Boolean
        get() = this == Granted || this == Partial
}
