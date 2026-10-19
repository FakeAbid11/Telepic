package com.telepic.ui.screens.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The two markers the map draws: a single photo's pin, and the count bubble that stands for a
 * cluster. Both are drawn here — pure `android.graphics`, no icon pack, no layout inflation — so a
 * marker is generated once per clustering pass and costs nothing to move.
 *
 * They are drawn as **one system**. A single photo used to fall back to osmdroid's stock teardrop
 * marker while a cluster wore the app's own circle, so the same map showed a stock green teardrop
 * beside an app-blue bubble: two shapes, two colours, two weights, for one kind of thing.
 */
object MapPinBadge {

    /**
     * The fill every marker is drawn with.
     *
     * Fixed, not theme-derived, because the map tiles are OSM Mapnik — a **light** style that does
     * not follow the app theme. In the dark scheme `primary` is a light blue (#7FA9FF) that measures
     * about 1.9:1 against a typical land tile, so the clusters all but disappeared. The brand blue
     * reads on the tiles in either theme.
     */
    val PinFill = Color(0xFF2F6BF0)

    /**
     * The ring drawn around every marker.
     *
     * White, so a marker keeps its edge over water, parks and dense streets alike — the same
     * reason the map's own floating controls are near-white with a dark glyph.
     */
    val PinRing = Color.White

    /** The count on a cluster bubble: white on [PinFill], which measures 4.68:1. */
    val PinCount = Color.White

    /**
     * The marker for a single photo: a teardrop in [PinFill] with a white ring.
     *
     * The teardrop shape is kept because it carries meaning a circle does not — it points *at* a
     * spot — while the colour and the ring are what make it belong to the same family as the
     * cluster bubbles.
     *
     * Drawn as a disc and a tail rather than as one bezier path: the white backing is drawn first
     * and slightly larger, and the blue on top of it, so the ring is an outline with no dependence
     * on how the two shapes wind or on an arc's direction.
     */
    fun singlePinDrawable(
        context: Context,
        fillColor: Int = PinFill.toArgb(),
        ringColor: Int = PinRing.toArgb(),
    ): Drawable {
        val density = context.resources.displayMetrics.density
        val radius = 8f * density
        val ring = 2f * density
        // From the head's centre down to the tip. Long enough that the point reads as a point and
        // not as a bump on the disc.
        val tailLength = 14f * density
        val headCy = radius + ring
        val tipY = headCy + tailLength
        val width = (2f * (radius + ring)).toInt().coerceAtLeast(1)
        val height = (tipY + ring).toInt().coerceAtLeast(1)
        val cx = width / 2f
        // The tail starts inside the head, so it emerges from it rather than being stuck onto it.
        val tailBase = headCy + radius * 0.5f

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ringColor }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }

        // The white backing: the disc plus a wider tail, so the white shows along the tail's flanks.
        canvas.drawCircle(cx, headCy, radius + ring, ringPaint)
        canvas.drawPath(tailPath(cx, tailBase, radius * 0.44f, tipY), ringPaint)

        // The blue on top of it: the disc plus a narrower tail reaching the same tip, so the point
        // stays blue and the ring is an outline.
        canvas.drawCircle(cx, headCy, radius, fillPaint)
        canvas.drawPath(tailPath(cx, tailBase, radius * 0.30f, tipY), fillPaint)

        return BitmapDrawable(context.resources, bitmap)
    }

    /** The tail of the pin: a triangle from the head's lower flanks down to the tip. */
    private fun tailPath(cx: Float, baseY: Float, halfWidth: Float, tipY: Float): Path = Path().apply {
        moveTo(cx - halfWidth, baseY)
        lineTo(cx + halfWidth, baseY)
        lineTo(cx, tipY)
        close()
    }

    /**
     * The count bubble for a cluster: a filled circle in [fillColor] with a white ring and the
     * number of photos inside it.
     *
     * [textColor] is required rather than defaulted: the fill is a mid-blue, and a white count on it
     * measures 4.68:1 — the same figure the single pin's ring is measured against.
     */
    fun clusterDrawable(
        context: Context,
        count: Int,
        fillColor: Int = PinFill.toArgb(),
        textColor: Int = PinCount.toArgb(),
        ringColor: Int = PinRing.toArgb(),
    ): Drawable {
        val density = context.resources.displayMetrics.density
        // `fontScale` as well as `density`: this is a number the user has to read, so it follows their
        // font-size setting like every other number in the app. Scaling on density alone left the
        // count at 12dp while the rest of the UI doubled at a 200% font scale — the same mistake
        // `TelegramStep` documents avoiding for its country field.
        val fontScale = context.resources.configuration.fontScale
        val textSize = (if (count < 100) 12f else 11f) * density * fontScale
        val pad = 4f * density
        val ring = 2f * density

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textAlign = Paint.Align.CENTER
            this.textSize = textSize
            isFakeBoldText = true
        }
        // The circle has to grow with its own digits or a 3-digit count spills over the edge, and
        // growing the text without the circle is exactly what a larger font scale would do. Measuring
        // the real string is cheaper than guessing a per-digit width, and it stays correct for a
        // 4-digit count.
        val baseRadius = (if (count < 100) 15f else 18f) * density
        val radius = maxOf(baseRadius, textPaint.measureText(count.toString()) / 2f + pad)
        // The ring is drawn *outside* the fill, so the bitmap carries it: a marker without its ring
        // loses its edge over a pale tile.
        val size = (2f * (radius + ring) + 2f * pad).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centre = size / 2f
        canvas.drawCircle(
            centre,
            centre,
            radius + ring,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ringColor },
        )
        canvas.drawCircle(centre, centre, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor })
        val baseline = centre - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(count.toString(), centre, baseline, textPaint)
        return BitmapDrawable(context.resources, bitmap)
    }
}
