package com.telepix.navigation

/**
 * Navigation contract for the media viewer.
 *
 * Only a stable MediaStore identifier travels through navigation — never a bitmap or the full
 * media object. Later phases expand this into the real viewer while keeping the same contract.
 */
object ViewerRoute {
    const val BASE = "viewer"
    const val ARG_MEDIA_ID = "mediaId"
    const val PATTERN = "$BASE/{$ARG_MEDIA_ID}"

    fun create(mediaId: Long): String = "$BASE/$mediaId"
}
