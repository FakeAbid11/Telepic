package com.telepix.domain.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies MIME-based cloud classification keeps GIF distinct and ignores unsupported media. */
class CloudMediaKindTest {

    @Test
    fun `image and video types`() {
        assertEquals(CloudMediaType.IMAGE, CloudMediaKind.classify("image/jpeg"))
        assertEquals(CloudMediaType.IMAGE, CloudMediaKind.classify("image/heic"))
        assertEquals(CloudMediaType.VIDEO, CloudMediaKind.classify("video/mp4"))
    }

    @Test
    fun `gif stays distinct`() {
        assertEquals(CloudMediaType.GIF, CloudMediaKind.classify("image/gif"))
        assertEquals(CloudMediaType.GIF, CloudMediaKind.classify("video/mp4", knownGif = true))
    }

    @Test
    fun `unsupported media is ignored`() {
        assertNull(CloudMediaKind.classify("application/pdf"))
        assertNull(CloudMediaKind.classify(null))
    }
}
