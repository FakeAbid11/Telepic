package com.telepic.data.cloud

import com.telepic.domain.backup.RemoteMediaIdentity
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudPreview
import com.telepic.domain.cloud.CloudStatus
import com.telepic.domain.cloud.CloudUploadProgress
import com.telepic.domain.cloud.CloudUploadRequest
import com.telepic.domain.cloud.CloudUploadResult
import com.telepic.domain.cloud.LocalDownloadedMedia
import com.telepic.domain.cloud.TelepicCloudDestination
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Cloud layer boundary consumed by the ViewModels and the backup engine. Tackles Telegram as a
 * remote media library — discovery, validated/persisted destination, incremental newest-first
 * browsing, previews vs explicit original download — plus the single upload operation the Phase 6
 * backup engine needs. It exposes no TDLib request shapes.
 */
interface CloudRepository {
    val status: StateFlow<CloudStatus>
    val media: Flow<List<CloudMedia>>

    /** Title of the validated destination once resolved (for the header). */
    val destinationTitle: kotlinx.coroutines.flow.StateFlow<String?>

    /** First pass after auth/destination: resolve destination and load the newest page. */
    suspend fun prepare()

    /** Verify auth + destination, pull the latest cloud messages, update the manifest + state. */
    suspend fun refresh()

    /** Resolve the Telepic Backup destination (reuse → discover → create), persisting it. */
    suspend fun ensureDestination(): TelepicCloudDestination?

    /**
     * Make the persisted cloud state belong to [accountId].
     *
     * Returns true when a destination from a different account was found and cleared. The caller
     * must then reset account-scoped backup/restore state. A null legacy account id is adopted, not
     * cleared, so an upgrade keeps its existing channel.
     *
     * Default false so test fakes that do not model accounts need no implementation.
     */
    suspend fun reconcileAccount(accountId: Long): Boolean = false

    /**
     * Recognition's manifest lookup, exposed for the upload path's pre-send backstop: the remote
     * identity whose content hash and byte count match exactly, or null when this install knows of
     * none. The default null ("nothing known") keeps test fakes honest — only a manifest-backed
     * implementation can ever answer with a match.
     *
     * The real implementation **rebuilds the manifest from the channel's history first** when this
     * install holds no rows for the destination yet — the reinstall case, where Room is wiped but
     * the channel is not. A lookup against a manifest that was never rebuilt would answer "nothing
     * known" and invite a second copy of everything already uploaded.
     */
    suspend fun findRemoteByContent(contentHash: String, sizeBytes: Long): RemoteMediaIdentity? = null

    /**
     * Confirms the same-name channel offered by [CloudStatus.DestinationConfirmation]: persists it as
     * the destination and refreshes. A no-op while no offer is outstanding, so a stale tap can never
     * persist a channel the user has not just been shown.
     */
    suspend fun confirmDestination(): TelepicCloudDestination?

    /**
     * Declines the offered same-name channel — Telepic then creates a channel this account owns,
     * which is what the discovery path does when nothing usable is found. Same no-op guard.
     */
    suspend fun createNewDestination(): TelepicCloudDestination?

    suspend fun getPreview(media: CloudMedia): CloudPreview?

    suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia?

    /**
     * Upload a staged media file to the validated destination. Ensures the destination first;
     * returns null when it cannot (unauthenticated / no destination), or throws [CloudNetworkException]
     * for transient failures and [CloudUploadRejectedException] for permanent ones. On success the
     * resulting item is added to the cloud manifest. The returned identity is the only thing that
     * lets a queue item become BACKED_UP.
     *
     * [onSent] fires as soon as TDLib accepts the send, with the real (chatId, messageId) — the
     * backup engine persists it immediately so a later timeout confirms that message via
     * [confirmUpload] instead of re-sending a duplicate.
     */
    suspend fun uploadMedia(
        request: CloudUploadRequest,
        onProgress: (CloudUploadProgress) -> Unit = {},
        onSent: suspend (chatId: Long, messageId: Long) -> Unit = { _, _ -> },
    ): CloudUploadResult?

    /**
     * Re-check a previously accepted send without uploading again. Returns the confirmed identity
     * (and records it in the manifest, preserving [contentHash]), throws
     * [CloudMessagePendingException] while Telegram is still delivering, [CloudMessageGoneException]
     * when the message never landed (only then is a re-send safe), or the usual transient/rejected
     * exceptions. Success is only ever reported from Telegram's real state.
     */
    suspend fun confirmUpload(
        chatId: Long,
        messageId: Long,
        mediaType: com.telepic.domain.cloud.CloudMediaType,
        contentHash: String?,
        contentSizeBytes: Long?,
    ): CloudUploadResult
}
