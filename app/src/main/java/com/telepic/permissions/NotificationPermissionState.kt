package com.telepic.permissions

/**
 * Whether Telepic may show the backup progress notification.
 *
 * Derived from Android's real state on every composition and re-read on resume — never persisted,
 * since the OS is the authoritative source (the user can flip it in system Settings at any time).
 */
enum class NotificationPermissionState {
    /** Below Android 13: notifications need no runtime permission. */
    NotRequired,

    /** Telepic may post notifications (the backup progress line will be visible). */
    Granted,

    /** Android 13+ and the channel-level toggle is off. */
    Denied,

    /**
     * Off, and asking again cannot help: the system stops showing the dialog after two denials, and
     * turning notifications off for the app in Settings has the same effect. Distinguished from
     * [Denied] because the only remaining recovery is system Settings — offering "Allow
     * notifications" here was a button that could never do anything.
     */
    PermanentlyDenied,
    ;

    /** Whether a progress notification can actually be shown to the user right now. */
    val canNotify: Boolean
        get() = this == NotRequired || this == Granted
}
