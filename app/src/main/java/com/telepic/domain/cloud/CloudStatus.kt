package com.telepic.domain.cloud

/**
 * The high-level status of the cloud layer, deliberately distinguishing conditions that must
 * never be conflated in the UI — most importantly "empty cloud" (no media) from "offline" or
 * "error" (Telegram unavailable), so the Cloud screen never shows an empty library for a failure.
 */
sealed interface CloudStatus {
    data object Initializing : CloudStatus
    data object NotAuthenticated : CloudStatus
    data object Connecting : CloudStatus
    data object Refreshing : CloudStatus
    data object Ready : CloudStatus
    data object Empty : CloudStatus
    data object Offline : CloudStatus
    data object DestinationMissing : CloudStatus
    data class DestinationInvalid(val reason: String) : CloudStatus
    data class Failed(val message: String) : CloudStatus
}

/** Immutable UI state for the Cloud screen. */
data class CloudUiState(
    val status: CloudStatus = CloudStatus.Initializing,
    val destinationTitle: String? = null,
    val media: List<CloudMedia> = emptyList(),
) {
    val hasMedia: Boolean get() = media.isNotEmpty()
}
