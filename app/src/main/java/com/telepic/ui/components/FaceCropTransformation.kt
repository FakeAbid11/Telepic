package com.telepic.ui.components

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation
import com.telepic.domain.face.FaceCrop

/**
 * Crops a photo down to one face, for the People grid's circular covers.
 *
 * **Why a Coil transformation rather than cropping at index time.** A cover is one small circle. Cutting
 * a bitmap per person when the index is built would store a thumbnail per face for a grid that may only
 * ever show fifty of them, and the user can change a cover at any time. Doing it at decode time means
 * the crop follows the current cover automatically and costs nothing until the card is actually on
 * screen.
 *
 * **Why normalized coordinates work here.** [FaceCrop]'s box is relative to the photo's own dimensions,
 * so it stays correct whatever size Coil decoded at — the crop is proportional, and the result is the
 * same face either way.
 *
 * The crop is squared around the face and given [marginFraction] of breathing room, because a circle
 * inscribed in a tight bounding box clips chins and foreheads. It is deliberately **not** warped onto
 * the ArcFace template: alignment exists to make embeddings comparable, and a warped face is a worse
 * thing to look at than a straight crop.
 */
class FaceCropTransformation(
    private val crop: FaceCrop,
    private val marginFraction: Float = 0.35f,
) : Transformation {

    /**
     * Keyed on the exact geometry, so Coil's memory cache can tell two covers of the same photo apart —
     * two people photographed together share a source bitmap but must not share a crop.
     */
    override val cacheKey: String
        get() = "faceCrop:${crop.faceId}:${crop.left}:${crop.top}:${crop.right}:${crop.bottom}:$marginFraction"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = crop.right - crop.left
        val height = crop.bottom - crop.top
        if (width <= 0f || height <= 0f) return input

        val marginX = width * marginFraction
        val marginY = height * marginFraction

        val left = ((crop.left - marginX) * input.width).toInt()
        val top = ((crop.top - marginY) * input.height).toInt()
        val right = ((crop.right + marginX) * input.width).toInt()
        val bottom = ((crop.bottom + marginY) * input.height).toInt()

        // Square around the box's centre, then clamp inside the decoded bitmap. Clamping *after*
        // squaring is what keeps the crop square at the edges of a photo, where the face is partly
        // outside the frame — the alternative shifts the crop and the face ends up off-centre.
        val side = maxOf(right - left, bottom - top)
        if (side <= 0) return input
        val clampedSide = minOf(side, input.width, input.height)
        val centreX = (left + right) / 2
        val centreY = (top + bottom) / 2
        val squareLeft = (centreX - clampedSide / 2).coerceIn(0, maxOf(0, input.width - clampedSide))
        val squareTop = (centreY - clampedSide / 2).coerceIn(0, maxOf(0, input.height - clampedSide))

        return try {
            Bitmap.createBitmap(input, squareLeft, squareTop, clampedSide, clampedSide)
        } catch (expected: IllegalArgumentException) {
            // A degenerate rect (a zero-width face, a bad box from a corrupt row) must degrade to the
            // whole photo rather than fail the image load and leave the card blank. Coil treats a
            // throwing transformation as a failed load, so this is the difference between one blank
            // card and a broken grid.
            input
        }
    }
}
