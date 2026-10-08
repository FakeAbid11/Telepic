package com.telepix.domain.backup

/** The stable Telegram identity of an already-backed-up remote record. */
data class RemoteMediaIdentity(
    val chatId: Long,
    val messageId: Long,
)

/** A computed content fingerprint: lowercase hex SHA-256 plus the content size used as a check. */
data class ContentHashResult(
    val sha256: String,
    val sizeBytes: Long,
)

/** Why recognition could not decide, so the caller never mistakes "unknown" for "backed up". */
enum class RecognitionUnavailableReason {
    /** The source could not be read (deleted, permission revoked, unreadable URI, I/O error). */
    SOURCE_UNREADABLE,

    /** Hashing failed transiently; retry later rather than treat the item as backed up. */
    HASH_FAILED,

    /** Remote metadata was inconsistent with the local content; do not auto-mark BACKED_UP. */
    REMOTE_METADATA_MISMATCH,
}

/**
 * The outcome of recognizing one local media item against the local queue and the remote manifest.
 * Recognition identity is content (SHA-256 + size) — never filename, path, date, album or device.
 */
sealed interface BackupRecognitionResult {
    /** Already stored in Telegram; carry the remote identity for local association. */
    data class AlreadyBackedUp(
        val localMediaId: String,
        val remote: RemoteMediaIdentity,
    ) : BackupRecognitionResult

    /** Not found remotely; queue it, carrying the freshly computed hash to persist on success. */
    data class NeedsBackup(
        val contentHash: String,
        val contentSizeBytes: Long,
    ) : BackupRecognitionResult

    /** An active queue operation already exists for this item — do not create another. */
    data class Pending(
        val queueItemId: Long,
    ) : BackupRecognitionResult

    /** Recognition could not safely conclude; the caller must not mark BACKED_UP. */
    data class Unavailable(
        val reason: RecognitionUnavailableReason,
    ) : BackupRecognitionResult
}
