package com.telepix.domain.backup

/**
 * The persistent lifecycle of a single backup operation. The only success path is
 * `NOT_BACKED_UP → QUEUED → PREPARING → UPLOADING → BACKED_UP`, and BACKED_UP is reached solely
 * after Telegram confirms the remote identity. Failures are terminal-permanent ([FAILED]) or
 * non-terminal waiting states ([WAITING_FOR_NETWORK], [WAITING_FOR_AUTH]) so a queue item is never
 * lost just because the network or login is temporarily down.
 */
enum class BackupState {
    NOT_BACKED_UP,
    QUEUED,
    PREPARING,
    UPLOADING,
    BACKED_UP,
    FAILED,
    CANCELLED,
    WAITING_FOR_NETWORK,
    WAITING_FOR_AUTH;

    val isTerminal: Boolean
        get() = this == BACKED_UP || this == CANCELLED || this == FAILED

    /** Whether a worker may actively pick this state up to do work (vs waiting/done). */
    val isActionable: Boolean
        get() = this == QUEUED || this == PREPARING || this == UPLOADING ||
            this == WAITING_FOR_NETWORK || this == WAITING_FOR_AUTH

    companion object {
        fun fromName(name: String?): BackupState? =
            entries.firstOrNull { it.name == name }
    }
}
