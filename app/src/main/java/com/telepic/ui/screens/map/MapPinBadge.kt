package com.telepic.ui.screens.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * Draws the count badge used for map clusters: a filled circle with the number of photos inside,
 * grown in proportion to digit count. Pure android.graphics (no new dependencies, no layout
 * inflation) — the same result an icon pack would give, generated once per clustering pass.
 */
object MapPinBadge {

    fun clusterDrawable(
        context: Context,
        count: Int,
        fillColor: Int,
        textColor: Int = Color.WHITE,
    ): Drawable {
        val density = context.resources.displayMetrics.density
        val radius = (if (count < 100) 15f else 18f) * density
        val textSize = (if (count < 100) 12f else 11f) * density
        val pad = 4f * density
        val size = (2f * radius + 2f * pad).toInt().coerceAtLeast(1)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textAlign = Paint.Align.CENTER
            this.textSize = textSize
            isFakeBoldText = true
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawCircle(size / 2f, size / 2f, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor })
        val baseline = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(count.toString(), size / 2f, baseline, textPaint)
        return BitmapDrawable(context.resources, bitmap)
    }
}
