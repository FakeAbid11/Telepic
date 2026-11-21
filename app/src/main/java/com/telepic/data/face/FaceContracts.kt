package com.telepic.data.face

import android.graphics.Bitmap

/**
 * One detected face: where it is in the source image, how sure the detector is, and the five landmarks
 * needed to align it.
 *
 * [box] is in **normalized** 0..1 coordinates so it survives any decode size (see `FaceEntity`).
 * [landmarks] are five points in the same normalized space, ordered left eye, right eye, nose tip,
 * left mouth corner, right mouth corner — the order the ArcFace alignment template expects, and the
 * order YuNet emits.
 */
data class DetectedFace(
    val box: FaceBox,
    val landmarks: FloatArray,
    val confidence: Float,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetectedFace) return false
        return box == other.box &&
            landmarks.contentEquals(other.landmarks) &&
            confidence == other.confidence
    }

    override fun hashCode(): Int {
        var result = box.hashCode()
        result = 31 * result + landmarks.contentHashCode()
        result = 31 * result + confidence.hashCode()
        return result
    }
}

/** Normalized bounding box. [left] < [right], [top] < [bottom], all in 0..1. */
data class FaceBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height
}

/**
 * Finds faces in a bitmap.
 *
 * An interface rather than a concrete class for the same reason `LocationExtractor` is one: the whole
 * indexing pipeline is then testable with a fake, without a model file, a device or a native library.
 * Real inference is not unit-testable, and pretending otherwise produces tests that assert nothing.
 */
interface FaceDetector {
    /**
     * @return every face above the detector's confidence threshold, in descending confidence order.
     *   An empty list means "looked, found none" — never null.
     */
    fun detect(bitmap: Bitmap): List<DetectedFace>

    /** Releases native resources. The detector is unusable afterwards. */
    fun close()
}

/**
 * Turns an aligned face crop into a 512-float identity embedding.
 *
 * Implementations must L2-normalise their output (see [FaceEmbeddingCodec]), because clustering
 * compares embeddings with a plain dot product.
 */
interface FaceEmbedder {
    fun embed(alignedFace: Bitmap): FloatArray

    fun close()
}

/**
 * Crops a detected face out of a bitmap and straightens it onto the canonical 112x112 template.
 *
 * This is a separate step, and a mandatory one. GhostFaceNet's published accuracy assumes 5-point
 * similarity alignment; feeding it a plain rectangular crop loses more accuracy than any quantisation
 * decision in this package. Doing it in its own class keeps the geometry testable in isolation, which
 * is the only part of the pipeline that can be verified without a model.
 */
interface FaceAligner {
    /**
     * @param landmarks the five points from [DetectedFace], normalized 0..1
     * @return a 112x112 bitmap, or null if the transform is degenerate (collinear points)
     */
    fun align(bitmap: Bitmap, landmarks: FloatArray): Bitmap?

    /**
     * Crops the face box with a margin, without warping. Used for the People grid's cover thumbnails,
     * where a straight-on crop looks better than a warped one.
     */
    fun cropForCover(bitmap: Bitmap, box: FaceBox, marginFraction: Float = 0.25f): Bitmap?
}
