package com.telepix.domain.backup

/**
 * The compact, presentation-only backup state a media tile may show. It is derived from the
 * authoritative [BackupState] by the repository layer — **the UI never infers it** (e.g. from a raw
 * remote id). `BACKED_UP` here means the existing backup architecture already confirmed remote
 * success; it is never implied by hashing, queueing or upload start.
 */
enum class MediaBackupVisualState {
    NONE,
    QUEUED,
    UPLOADING,
    BACKED_UP,
    FAILED;

    companion object {
        /** Collapse the rich internal state machine into what a photo cell can usefully show. */
        fun from(state: BackupState?): MediaBackupVisualState = when (state) {
            BackupState.BACKED_UP -> BACKED_UP
            BackupState.PREPARING, BackupState.UPLOADING -> UPLOADING
            BackupState.FAILED -> FAILED
            BackupState.QUEUED, BackupState.WAITING_FOR_NETWORK, BackupState.WAITING_FOR_AUTH -> QUEUED
            else -> NONE // NOT_BACKED_UP / CANCELLED / null → no indicator
        }
    }
}
