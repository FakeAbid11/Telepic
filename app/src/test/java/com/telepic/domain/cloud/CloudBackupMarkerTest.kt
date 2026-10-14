package com.telepic.domain.cloud

import com.telepic.domain.backup.ContentHashResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The caption marker is the only thing that lets recognition survive a reinstall, so the interesting
 * cases are not "does it round-trip" but "what does it refuse". A parser that accepted a partial or
 * foreign caption would let the manifest claim a content identity nothing verified.
 */
class CloudBackupMarkerTest {

    private val hash = "a".repeat(64)

    @Test
    fun `encode then parse round-trips the identity`() {
        val caption = CloudBackupMarker.encode(hash, 4_194_304L)
        assertEquals("telepic/v1 sha256=$hash size=4194304", caption)
        assertEquals(ContentHashResult(hash, 4_194_304L), CloudBackupMarker.parse(caption))
    }

    @Test
    fun `parse tolerates surrounding whitespace`() {
        val caption = CloudBackupMarker.encode(hash, 10L)
        assertEquals(ContentHashResult(hash, 10L), CloudBackupMarker.parse("  $caption\n"))
    }

    @Test
    fun `encode refuses to stamp an incomplete or untrustworthy identity`() {
        // No hash: a marker with no content identity is noise in the user's channel that no parser
        // could use, so the caption stays empty.
        assertEquals("", CloudBackupMarker.encode(null, 10L))
        assertEquals("", CloudBackupMarker.encode(hash, null))
        // A zero or negative size cannot be the byte count a hash was computed over.
        assertEquals("", CloudBackupMarker.encode(hash, 0L))
        assertEquals("", CloudBackupMarker.encode(hash, -1L))
        // A non-hex or wrong-length "hash" is not a SHA-256.
        assertEquals("", CloudBackupMarker.encode("z".repeat(64), 10L))
        assertEquals("", CloudBackupMarker.encode("a".repeat(63), 10L))
        assertEquals("", CloudBackupMarker.encode("A".repeat(64), 10L))
    }

    @Test
    fun `parse rejects anything that is not exactly a Telepic marker`() {
        assertNull(CloudBackupMarker.parse(null))
        assertNull(CloudBackupMarker.parse(""))
        assertNull(CloudBackupMarker.parse("   "))
        // A user's own caption.
        assertNull(CloudBackupMarker.parse("Holiday 2026"))
        // Right shape, wrong version.
        assertNull(CloudBackupMarker.parse("telepic/v2 sha256=$hash size=10"))
        // Missing the size half.
        assertNull(CloudBackupMarker.parse("telepic/v1 sha256=$hash"))
        // Uppercase hex is not the canonical form.
        assertNull(CloudBackupMarker.parse("telepic/v1 sha256=${"A".repeat(64)} size=10"))
        // A short hash must not be accepted as a prefix.
        assertNull(CloudBackupMarker.parse("telepic/v1 sha256=${"a".repeat(32)} size=10"))
        // Trailing content means this is not a marker Telepic wrote.
        assertNull(CloudBackupMarker.parse("telepic/v1 sha256=$hash size=10 and more"))
        // Leading content, too.
        assertNull(CloudBackupMarker.parse("note telepic/v1 sha256=$hash size=10"))
        // Non-numeric, negative and zero sizes.
        assertNull(CloudBackupMarker.parse("telepic/v1 sha256=$hash size=abc"))
        assertNull(CloudBackupMarker.parse("telepic/v1 sha256=$hash size=-5"))
        assertNull(CloudBackupMarker.parse("telepic/v1 sha256=$hash size=0"))
    }

    /**
     * A size that cannot fit a `Long` must fail to match rather than overflow into a nonsense value
     * that would then be compared against a real file size.
     */
    @Test
    fun `parse rejects a size that overflows a Long`() {
        assertNull(CloudBackupMarker.parse("telepic/v1 sha256=$hash size=${"9".repeat(20)}"))
    }

    @Test
    fun `parse accepts the largest representable size`() {
        assertEquals(Long.MAX_VALUE, CloudBackupMarker.parse("telepic/v1 sha256=$hash size=${Long.MAX_VALUE}")?.sizeBytes)
    }
}
