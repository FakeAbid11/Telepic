package com.telepic.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build-time guard for the contrast ratios the theme documents in prose.
 *
 * These tests fail if a color token is changed without re-measuring its accessibility impact.
 * The math follows WCAG 2.1: relative luminance in linear RGB, then (L1 + 0.05) / (L2 + 0.05).
 */
class ContrastTest {

    // ---- On-media tokens (scheme-independent, drawn on photos/videos) ----

    @Test
    fun `white text on chromeScrim over a white photo meets AA`() {
        // chromeScrim = 70% black on top of a white photo → #4D4D4D
        val chromeOverWhite = blend(Color.Black, 0xB3 / 255f, Color.White)
        assertRatio("white on chromeScrim/white", Color.White, chromeOverWhite, 4.5)
    }

    @Test
    fun `white ring on chromeScrim over a white photo meets graphics AA`() {
        val chromeOverWhite = blend(Color.Black, 0xB3 / 255f, Color.White)
        assertRatio("white ring on chromeScrim/white", Color.White, chromeOverWhite, 3.0)
    }

    @Test
    fun `favorite amber on chromeScrim over a white photo meets graphics AA`() {
        val chromeOverWhite = blend(Color.Black, 0xB3 / 255f, Color.White)
        assertRatio("favoriteAmber on chromeScrim/white", Color(0xFFFFC53D), chromeOverWhite, 3.0)
    }

    @Test
    fun `white on light scrim over a white photo fails graphics floor`() {
        // This documents the *old* defect: scrim in light mode is only 40% black.
        val scrimOverWhite = blend(Color.Black, 0x66 / 255f, Color.White)
        val ratio = contrastRatio(Color.White, scrimOverWhite)
        assertTrue(
            "white on scrim(light)/white was %.2f, expected < 3.0 (documents the old defect)".format(ratio),
            ratio < 3.0,
        )
    }

    @Test
    fun `media error text on black viewer backdrop meets AA`() {
        assertRatio("mediaErrorText on black", Color(0xFFFF8A80), Color.Black, 4.5)
    }

    @Test
    fun `media success text on black viewer backdrop meets AA`() {
        assertRatio("mediaSuccessText on black", Color(0xFF7FE0A6), Color.Black, 4.5)
    }

    @Test
    fun `media warning text on black viewer backdrop meets AA`() {
        assertRatio("mediaWarningText on black", Color(0xFFFFB74D), Color.Black, 4.5)
    }

    // ---- Scheme pairs ----

    @Test
    fun `dark scheme onPrimary on primary meets AA`() {
        assertRatio("dark onPrimary/primary", Color(0xFF08193D), Color(0xFF7FA9FF), 4.5)
    }

    @Test
    fun `dark scheme onSurface on background meets AA`() {
        assertRatio("dark onSurface/bg", Color(0xFFE7EAF0), Color(0xFF0B0D10), 4.5)
    }

    @Test
    fun `dark scheme onSurfaceVariant on background meets AA`() {
        assertRatio("dark onSurfaceVariant/bg", Color(0xFFC2C8D2), Color(0xFF0B0D10), 4.5)
    }

    @Test
    fun `light scheme onPrimary on primary meets AA`() {
        assertRatio("light onPrimary/primary", Color.White, Color(0xFF2F6BF0), 4.5)
    }

    @Test
    fun `light scheme onSurface on background meets AA`() {
        assertRatio("light onSurface/bg", Color(0xFF0F1216), Color.White, 4.5)
    }

    @Test
    fun `light scheme onSurfaceVariant on background meets AA`() {
        assertRatio("light onSurfaceVariant/bg", Color(0xFF43474E), Color.White, 4.5)
    }

    // ---- helpers ----

    private fun assertRatio(label: String, fg: Color, bg: Color, min: Double) {
        val ratio = contrastRatio(fg, bg)
        assertTrue(
            "%s = %.2f, need >= %.1f".format(label, ratio, min),
            ratio >= min,
        )
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun relativeLuminance(c: Color): Double {
        fun channel(v: Float): Double {
            return if (v <= 0.03928f) v / 12.92 else Math.pow(((v + 0.055) / 1.055).toDouble(), 2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun blend(fg: Color, alpha: Float, bg: Color): Color {
        return Color(
            red = fg.red * alpha + bg.red * (1 - alpha),
            green = fg.green * alpha + bg.green * (1 - alpha),
            blue = fg.blue * alpha + bg.blue * (1 - alpha),
        )
    }
}
