package com.telepic.data.face

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect

/**
 * Straightens a detected face onto the canonical 112x112 ArcFace template.
 *
 * This is a mandatory step, not a refinement. GhostFaceNet's published accuracy assumes 5-point
 * similarity alignment; a plain rectangular crop leaves the eyes at whatever angle and scale the
 * photograph happened to have, and measured on a labelled set that costs more accuracy than every
 * quantisation decision in this package combined.
 *
 * The template below is the standard ArcFace reference points. It is the same matrix the Python
 * calibration tooling computes (`tools/face_models/compare_quantisation.py`), so a threshold measured
 * there transfers to what this class produces.
 *
 * A **similarity** transform is used (rotation + uniform scale + translation), not a full affine. A
 * full affine has six degrees of freedom and will happily shear a face to fit the template, which
 * distorts the very geometry the embedding encodes. Similarity keeps the face's shape intact.
 */
class ArcFaceAligner : FaceAligner {

    override fun align(bitmap: Bitmap, landmarks: FloatArray): Bitmap? {
        require(landmarks.size == 10) { "expected 10 landmark values, got ${landmarks.size}" }

        val source = FloatArray(10) { index ->
            val point = index / 2
            val isX = index % 2 == 0
            if (isX) landmarks[index] * bitmap.width else landmarks[index] * bitmap.height
        }

        val matrix = similarityTransform(source) ?: return null
        return render(bitmap, matrix)
    }

    override fun cropForCover(bitmap: Bitmap, box: FaceBox, marginFraction: Float): Bitmap? {
        val width = box.width * bitmap.width
        val height = box.height * bitmap.height
        if (width < 1f || height < 1f) return null

        val marginX = width * marginFraction
        val marginY = height * marginFraction
        val left = (box.left * bitmap.width - marginX).coerceAtLeast(0f)
        val top = (box.top * bitmap.height - marginY).coerceAtLeast(0f)
        val right = (box.right * bitmap.width + marginX).coerceAtMost(bitmap.width.toFloat())
        val bottom = (box.bottom * bitmap.height + marginY).coerceAtMost(bitmap.height.toFloat())

        val sourceRect = Rect(
            left.toInt(),
            top.toInt(),
            right.toInt().coerceAtLeast(left.toInt() + 1),
            bottom.toInt().coerceAtLeast(top.toInt() + 1),
        )
        if (sourceRect.width() <= 0 || sourceRect.height() <= 0) return null

        // Square the crop so a card's cover is not stretched by whatever aspect the face happened to
        // have. Centred on the box, clamped to the image.
        val side = maxOf(sourceRect.width(), sourceRect.height())
        val centreX = sourceRect.centerX()
        val centreY = sourceRect.centerY()
        val squareLeft = (centreX - side / 2).coerceIn(0, maxOf(0, bitmap.width - side))
        val squareTop = (centreY - side / 2).coerceIn(0, maxOf(0, bitmap.height - side))
        val squareSide = minOf(side, bitmap.width, bitmap.height)
        if (squareSide <= 0) return null

        return try {
            Bitmap.createBitmap(bitmap, squareLeft, squareTop, squareSide, squareSide)
        } catch (expected: IllegalArgumentException) {
            // The contract is "null when no square crop exists", so a degenerate source rect is a
            // result rather than a failure. Nothing is logged: this runs per face per frame while a
            // grid scrolls, and a log line per tile would be noise, not a trace.
            null
        }
    }

    /**
     * Warps [bitmap] through [matrix] into a fresh 112x112 bitmap.
     *
     * `Bitmap.createBitmap(source, 0, 0, w, h, matrix, true)` would return a bitmap the size of the
     * *source*, transformed — not a template-sized crop. The output has to be exactly
     * [FaceEmbeddingCodec]'s input, so the warp is drawn into a bitmap of that size instead.
     */
    private fun render(bitmap: Bitmap, matrix: Matrix): Bitmap? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        return try {
            Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888).also { target ->
                val canvas = android.graphics.Canvas(target)
                // Fill black first: `warpAffine(..., borderValue=0)` in the reference does the same,
                // and an unpainted region would otherwise be transparent black with arbitrary alpha.
                canvas.drawColor(android.graphics.Color.BLACK)
                canvas.drawBitmap(bitmap, matrix, android.graphics.Paint().apply { isFilterBitmap = true })
            }
        } catch (expected: IllegalArgumentException) {
            // A degenerate transform (zero scale) throws rather than returning null; null is the
            // function's documented "could not align" result.
            null
        }
    }

    /**
     * Least-squares similarity transform from [source] points onto the template.
     *
     * This is the closed-form Umeyama solution specialised to 2-D similarity, which is what
     * `cv2.estimateAffinePartial2D` computes. Doing it explicitly rather than through an Android API
     * is deliberate: the platform has no similarity-only estimator, and `Matrix.setPolyToPoly` with
     * five correspondences produces a full projective transform that shears.
     *
     * Returns null when the source points are degenerate (all coincident), because the scale is then
     * undefined and any matrix would be a guess.
     */
    private fun similarityTransform(source: FloatArray): Matrix? {
        val count = TEMPLATE.size / 2

        var sourceMeanX = 0f
        var sourceMeanY = 0f
        var templateMeanX = 0f
        var templateMeanY = 0f
        for (point in 0 until count) {
            sourceMeanX += source[point * 2]
            sourceMeanY += source[point * 2 + 1]
            templateMeanX += TEMPLATE[point * 2]
            templateMeanY += TEMPLATE[point * 2 + 1]
        }
        sourceMeanX /= count
        sourceMeanY /= count
        templateMeanX /= count
        templateMeanY /= count

        // Cross-covariance sums and the source variance, accumulated about the means.
        var sxx = 0f
        var sxy = 0f
        var syx = 0f
        var syy = 0f
        var sourceVariance = 0f
        for (point in 0 until count) {
            val sx = source[point * 2] - sourceMeanX
            val sy = source[point * 2 + 1] - sourceMeanY
            val tx = TEMPLATE[point * 2] - templateMeanX
            val ty = TEMPLATE[point * 2 + 1] - templateMeanY

            sxx += sx * tx
            sxy += sx * ty
            syx += sy * tx
            syy += sy * ty
            sourceVariance += sx * sx + sy * sy
        }

        if (sourceVariance <= 1e-6f) return null

        // Umeyama: rotation is recovered from the polar decomposition of the cross-covariance, which
        // in 2-D has this closed form. The sign convention matters: `atan2(sxy - syx, trace)` is the
        // rotation that maps source onto template, while the mirrored form `syx - sxy` rotates the
        // wrong way and produces a visibly crooked crop. Verified against
        // `cv2.estimateAffinePartial2D` on rotations from -25° to 180°; agreement is exact.
        val trace = sxx + syy
        val rotationAngle = kotlin.math.atan2(sxy - syx, trace)
        val scale = kotlin.math.sqrt(trace * trace + (sxy - syx) * (sxy - syx)) / sourceVariance

        if (!scale.isFinite() || scale <= 1e-6f) return null

        val cos = kotlin.math.cos(rotationAngle) * scale
        val sin = kotlin.math.sin(rotationAngle) * scale

        return Matrix().apply {
            setValues(
                floatArrayOf(
                    cos,
                    -sin,
                    templateMeanX - cos * sourceMeanX + sin * sourceMeanY,
                    sin,
                    cos,
                    templateMeanY - sin * sourceMeanX - cos * sourceMeanY,
                    0f,
                    0f,
                    1f,
                ),
            )
        }
    }

    private companion object {
        /**
         * Canonical 112x112 five-point template: left eye, right eye, nose tip, left mouth corner,
         * right mouth corner — the same order YuNet emits and the Python tooling assumes.
         */
        val TEMPLATE = floatArrayOf(
            38.2946f, 51.6963f,
            73.5318f, 51.5014f,
            56.0252f, 71.7366f,
            41.5493f, 92.3655f,
            70.7299f, 92.2041f,
        )

        const val INPUT_SIZE = 112
    }
}
