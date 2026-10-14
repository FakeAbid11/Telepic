package com.telepic.domain.cloud

import com.telepic.domain.backup.ContentHashResult

/**
 * The content identity Telepic stamps into a backed-up message's caption, and the only way that
 * identity survives a reinstall.
 *
 * ## Why this exists
 *
 * Recognition answers "is this local file already in Telegram?" by comparing a **locally computed**
 * SHA-256 against the manifest. Within one install that works from the queue and the manifest. Across
 * a reinstall it does not: the manifest is empty, so it is rebuilt from the channel's history — and
 * the history is just Telegram messages. A rediscovered row therefore has no hash, and the local side
 * can never compute the *remote* file's hash without downloading it. The result was that every item
 * was re-uploaded.
 *
 * The fix is to make the upload self-describing: Telepic writes the hash and the **original** byte
 * count into the caption of the message it sends. Discovery then reads them back, so a rebuilt
 * manifest is as complete as the original one. This is what makes the "recognition across reinstall"
 * guarantee in `CloudMediaManifestEntity` true rather than aspirational.
 *
 * ## Why a caption
 *
 * Telegram offers no arbitrary metadata carrier on a photo/video/animation message: TDLib's
 * `InputMessageDocument` has `attributes`, but sending media as a document would cost the inline
 * preview in the user's own channel, and `InputMessagePhoto`/`Video`/`Animation` expose only the
 * caption. So the caption is the only field available, and the format below is chosen to be
 * unambiguous, strictly parseable, and short enough to sit under a thumbnail without dominating it.
 *
 * ## Why parsing a caption is safe
 *
 * The manifest's rule is that a hash comes only from Telepic's own pipeline, never from arbitrary
 * Telegram content. A strict marker keeps that true: [parse] accepts only an exact-prefix, exactly
 * 64 lowercase hex characters and a positive integer, and the value it returns is used **only as a
 * lookup key**. Recognition still hashes the local file independently and requires the sizes to
 * agree, so a forged or corrupted caption can at worst fail to match — it can never make Telepic
 * skip an upload for content it does not already hold. Forging a *matching* caption would require
 * already knowing the SHA-256 of the local file, i.e. already holding its bytes.
 */
object CloudBackupMarker {

    /** Versioned so a future format change is detectable rather than silently mis-parsed. */
    const val PREFIX: String = "telepic/v1"

    private const val HASH_KEY = "sha256="
    private const val SIZE_KEY = " size="

    /**
     * `telepic/v1 sha256=<64 lowercase hex> size=<positive integer>` — and nothing else.
     *
     * `[0-9]{1,19}` rather than `\d+` so a value that cannot fit a `Long` fails to match here
     * instead of overflowing later; the captured text is still parsed with [Long.toLongOrNull].
     */
    private val PATTERN = Regex("^$PREFIX $HASH_KEY([0-9a-f]{64})$SIZE_KEY([0-9]{1,19})$")

    /**
     * The caption to attach to an upload, or an empty string when there is nothing trustworthy to
     * stamp. An empty caption is the correct degradation: a marker without a hash would be noise in
     * the user's channel that no parser could use.
     *
     * [sizeBytes] must be the byte count the hash was computed over (the *original* size), never
     * Telegram's recompressed size for the same media — the two disagree for photos, and the whole
     * point of recording it is that a later local file can be compared against it.
     */
    fun encode(hash: String?, sizeBytes: Long?): String {
        if (hash == null || sizeBytes == null || sizeBytes <= 0L) return ""
        if (!PATTERN.matches("$PREFIX $HASH_KEY$hash$SIZE_KEY$sizeBytes")) return ""
        return "$PREFIX $HASH_KEY$hash$SIZE_KEY$sizeBytes"
    }

    /**
     * The identity a caption carries, or `null` when it is absent, malformed, or not Telepic's.
     *
     * Deliberately total: any caption a user could type, any caption from another app, and any
     * partially-written marker all return `null` rather than a partial identity.
     */
    fun parse(caption: String?): ContentHashResult? {
        val match = PATTERN.matchEntire(caption?.trim() ?: return null) ?: return null
        val sizeBytes = match.groupValues[2].toLongOrNull() ?: return null
        if (sizeBytes <= 0L) return null
        return ContentHashResult(sha256 = match.groupValues[1], sizeBytes = sizeBytes)
    }
}
