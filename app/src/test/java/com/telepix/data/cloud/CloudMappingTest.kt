package com.telepix.data.cloud

import com.telepix.data.cloud.CloudMapping.toEntity
import com.telepix.data.cloud.CloudMapping.toDomain
import com.telepix.domain.cloud.CloudMedia
import com.telepix.domain.cloud.CloudMediaType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies the manifest round-trip preserves the stable remote identity and metadata. */
class CloudMappingTest {

    private val media = CloudMedia(
        messageId = 42L,
        chatId = 7L,
        mediaType = CloudMediaType.VIDEO,
        mimeType = "video/mp4",
        fileName = "clip.mp4",
        sizeBytes = 12345L,
        width = 640,
        height = 480,
        durationMs = 24_000L,
        dateEpochSec = 1_700_000_000L,
        previewFileId = 3,
        originalFileId = 9,
        isDownloaded = false,
    )

    @Test
    fun `round trip preserves identity and fields`() {
        val restored = media.toEntity(nowMillis = 1L).toDomain()
        assertEquals(media, restored)
    }

    @Test
    fun `remote identity is chatId + messageId, not filename`() {
        val entity = media.toEntity(nowMillis = 1L)
        assertEquals(7L, entity.chatId)
        assertEquals(42L, entity.messageId)
    }
}
