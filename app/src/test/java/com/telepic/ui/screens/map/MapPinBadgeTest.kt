package com.telepic.ui.screens.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The map's markers, drawn rather than themed.
 *
 * They are worth a test because nothing else guards them: a single photo used to fall back to
 * osmdroid's *stock* teardrop marker while a cluster wore the app's own circle, so the same map
 * carried two visual languages. And the fill is deliberately **not** the theme's `primary`: the tiles
 * are OSM Mapnik, a fixed light style, and the dark scheme's light blue measured about 1.9:1 against
 * a typical land tile — the clusters all but disappeared.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class MapPinBadgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the single pin is drawn in the brand blue with a white ring`() {
        val bitmap = (MapPinBadge.singlePinDrawable(context) as BitmapDrawable).bitmap

        assertTrue(
            "the marker must be filled with the brand blue",
            hasPixelNear(bitmap, MapPinBadge.PinFill.toArgb()),
        )
        assertTrue(
            "the marker must carry a white ring",
            hasPixelNear(bitmap, MapPinBadge.PinRing.toArgb()),
        )
    }

    @Test
    fun `the single pin is taller than it is wide, so it reads as a point`() {
        val bitmap = (MapPinBadge.singlePinDrawable(context) as BitmapDrawable).bitmap
        assertTrue(
            "a pin must be taller than wide (was ${bitmap.width}x${bitmap.height})",
            bitmap.height > bitmap.width,
        )
    }

    @Test
    fun `the cluster bubble carries the count in the same family as the single pin`() {
        val single = (MapPinBadge.singlePinDrawable(context) as BitmapDrawable).bitmap
        val cluster = (MapPinBadge.clusterDrawable(context, 12) as BitmapDrawable).bitmap

        // Same fill and ring as the single pin: one system on one map.
        assertTrue(
            "the single pin must be filled with the brand blue",
            hasPixelNear(single, MapPinBadge.PinFill.toArgb()),
        )
        assertTrue(
            "the cluster bubble must be filled with the brand blue",
            hasPixelNear(cluster, MapPinBadge.PinFill.toArgb()),
        )
        assertTrue(
            "the cluster bubble must carry the same white ring",
            hasPixelNear(cluster, MapPinBadge.PinRing.toArgb()),
        )
    }

    @Test
    fun `a three-digit count still leaves its ring intact`() {
        val bitmap = (MapPinBadge.clusterDrawable(context, 987) as BitmapDrawable).bitmap
        // The count is drawn on the fill, so the ring must survive around it.
        assertTrue(
            "a three-digit count must not swallow the ring",
            hasPixelNear(bitmap, MapPinBadge.PinRing.toArgb()),
        )
    }

    @Test
    fun `the brand blue is readable on the light map tiles`() {
        // The tiles are a fixed light style, so the markers cannot follow the app theme. The brand
        // blue on a typical land tile measures about 4.7:1.
        val land = 0xFFF2EFE9.toInt()
        assertTrue(
            "the brand blue must read against a light land tile",
            contrast(MapPinBadge.PinFill.toArgb(), land) >= 3.0,
        )
    }

    @Test
    fun `the dark theme's primary would have vanished on those tiles`() {
        // The reason the fill is fixed: the dark scheme's `primary` is a light blue that measures
        // about 1.9:1 against a land tile.
        val land = 0xFFF2EFE9.toInt()
        val darkSchemePrimary = 0xFF7FA9FF.toInt()
        assertTrue(
            "the dark theme's primary is unusable on the light tiles, which is why the fill is fixed",
            contrast(darkSchemePrimary, land) < 3.0,
        )
    }

    /**
     * Whether the bitmap holds a pixel of (approximately) [argb].
     *
     * Approximate, because every edge in a drawn marker is antialiased: a blend of the fill and
     * the ring is neither colour, and demanding an exact match would fail on the very pixels that
     * prove the marker was drawn. The tolerance is tight enough to reject a fill/ring blend and
     * loose enough to survive an antialiased outer edge.
     */
    private fun hasPixelNear(bitmap: Bitmap, argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.alpha(pixel) < 200) continue
                if (abs(((pixel shr 16) and 0xFF) - r) <= TOLERANCE &&
                    abs(((pixel shr 8) and 0xFF) - g) <= TOLERANCE &&
                    abs((pixel and 0xFF) - b) <= TOLERANCE
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun contrast(foreground: Int, background: Int): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun relativeLuminance(argb: Int): Double {
        fun channel(value: Int): Double {
            val s = value / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private companion object {
        /** Per-channel tolerance for "is this pixel (approximately) that colour". */
        const val TOLERANCE = 24
    }
}
