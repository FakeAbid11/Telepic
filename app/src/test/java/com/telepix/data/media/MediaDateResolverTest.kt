package com.telepix.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies timestamp normalization: DATE_TAKEN (ms) preferred, DATE_MODIFIED (s) fallback. */
class MediaDateResolverTest {

    @Test
    fun `prefers date taken in milliseconds`() {
        val taken = 1_700_000_000_000L
        assertEquals(taken, MediaDateResolver.resolveMillis(taken, 999L))
    }

    @Test
    fun `falls back to date modified converted from seconds`() {
        val modifiedSeconds = 1_600_000_000L
        assertEquals(
            modifiedSeconds * 1000L,
            MediaDateResolver.resolveMillis(null, modifiedSeconds),
        )
    }

    @Test
    fun `ignores non-positive date taken and uses modified`() {
        val modifiedSeconds = 1_600_000_000L
        assertEquals(
            modifiedSeconds * 1000L,
            MediaDateResolver.resolveMillis(0L, modifiedSeconds),
        )
    }

    @Test
    fun `returns zero when neither timestamp is available`() {
        assertEquals(0L, MediaDateResolver.resolveMillis(null, null))
        assertEquals(0L, MediaDateResolver.resolveMillis(0L, 0L))
    }
}
