package com.telepic.ui.screens.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * Draws the count badge used for map clusters: a filled circle with the number of photos inside,
 * grown in proportion to digit count. Pure android.graphics (no new dependencies, no layout
 * inflation) — the same result an icon pack would give, generated once per clustering pass.
 *
 * [textColor] is required rather than defaulted: the fill is the theme's `primary`, which is a
 * LIGHT tone in the dark scheme (#7FA9FF), so a white count would land at 2.33:1 contrast and be
 * unreadable. Callers must pass the matching `onPrimary`.
 */
object MapPinBadge {

    fun clusterDrawable(
        context: Context,
        count: Int,
        fillColor: Int,
        textColor: Int,
    ): Drawable {
        val density = context.resources.displayMetrics.density
        // `fontScale` as well as `density`: this is a number the user has to read, so it follows their
        // font-size setting like every other number in the app. Scaling on density alone left the
        // count at 12dp while the rest of the UI doubled at a 200% font scale — the same mistake
        // `TelegramStep` documents avoiding for its country field.
        val fontScale = context.resources.configuration.fontScale
        val textSize = (if (count < 100) 12f else 11f) * density * fontScale
        val pad = 4f * density

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
        val size = (2f * radius + 2f * pad).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawCircle(size / 2f, size / 2f, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor })
        val baseline = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(count.toString(), size / 2f, baseline, textPaint)
        return BitmapDrawable(context.resources, bitmap)
    }
}
