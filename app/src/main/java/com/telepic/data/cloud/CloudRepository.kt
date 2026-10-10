package com.telepic.data.cloud

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

    suspend fun getPreview(media: CloudMedia): CloudPreview?

    suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia?

    /**
     * Upload a staged media file to the validated destination. Ensures the destination first;
     * returns null when it cannot (unauthenticated / no destination), or throws [CloudNetworkException]
     * for transient failures and [CloudUploadRejectedException] for permanent ones. On success the
     * resulting item is added to the cloud manifest. The returned identity is the only thing that
     * lets a queue item become BACKED_UP.
     */
    suspend fun uploadMedia(
        request: CloudUploadRequest,
        onProgress: (CloudUploadProgress) -> Unit = {},
    ): CloudUploadResult?
}
