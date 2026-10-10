package com.telepic.domain.backup

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
    FAILED,

    /** Automatic retries exhausted: the item waits forever unless the user acts (retry/fix auth). */
    STALLED;

    companion object {
        /**
         * Collapse the rich internal state machine into what a photo cell can usefully show.
         * A WAITING_FOR_NETWORK row whose automatic retry budget is exhausted is *not* "queued" —
         * nothing will pick it up again, so it surfaces as [STALLED] instead of pretending to progress.
         */
        fun from(
            state: BackupState?,
            retryCount: Int = 0,
            maxRetries: Int = Int.MAX_VALUE,
        ): MediaBackupVisualState = when (state) {
            BackupState.BACKED_UP -> BACKED_UP
            BackupState.PREPARING, BackupState.UPLOADING -> UPLOADING
            BackupState.FAILED -> FAILED
            BackupState.WAITING_FOR_NETWORK ->
                if (retryCount >= maxRetries) STALLED else QUEUED
            BackupState.QUEUED, BackupState.WAITING_FOR_AUTH -> QUEUED
            else -> NONE // NOT_BACKED_UP / CANCELLED / null → no indicator
        }
    }
}
