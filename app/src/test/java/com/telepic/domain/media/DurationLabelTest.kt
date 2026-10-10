package com.telepic.domain.media

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The duration badge must roll hours up instead of letting minutes run past 59, and must show
 * nothing at all when a duration is unknown — never a fabricated `0:00`. Asserted with an explicit
 * locale so a device with different digit rendering cannot change the expected strings.
 */
class DurationLabelTest {

    private val english: Locale = Locale.ENGLISH

    private fun withEnglish(block: () -> Unit) {
        val previous = Locale.getDefault()
        Locale.setDefault(english)
        try {
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `an hour plus minutes renders h colon mm colon ss`() = withEnglish {
        assertEquals("1:30:00", DurationLabel.format(5_400_000L))
        assertEquals("2:00:07", DurationLabel.format(7_207_000L))
    }

    @Test
    fun `under an hour stays m colon ss`() = withEnglish {
        assertEquals("1:05", DurationLabel.format(65_000L))
        assertEquals("59:59", DurationLabel.format(3_599_000L))
    }

    @Test
    fun `null, zero and negative durations render nothing`() = withEnglish {
        assertNull(DurationLabel.format(null))
        assertNull(DurationLabel.format(0L))
        assertNull(DurationLabel.format(-1L))
    }
}
