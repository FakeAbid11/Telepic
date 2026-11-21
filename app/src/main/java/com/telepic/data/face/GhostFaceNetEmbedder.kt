package com.telepic.data.face

import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GhostFaceNet V1 W1.3-S1 embedder over TensorFlow Lite.
 *
 * The graph is the upstream Keras `.h5` converted with `tools/face_models/convert_model.py` in
 * dynamic-range int8 mode. Two details come straight from upstream's `data.py`/`evals.py` and are
 * load-bearing:
 *
 * 1. **RGB, not BGR.** Upstream decodes with `tf.image.decode_image` and `skimage.io.imread`, both of
 *    which produce RGB. Measured on a labelled same/different set, feeding BGR instead costs 0.06 of
 *    separation margin (same-person min 0.725 → 0.658) — the largest single accuracy loss available
 *    in this class. Note this is the opposite of [YuNetFaceDetector], which wants BGR; the asymmetry
 *    is upstream's.
 * 2. **`(pixel - 127.5) * 0.0078125`.** No per-image normalisation, no ImageNet statistics.
 *
 * The output is L2-normalised here rather than at the call site. The raw vector has a norm around 13,
 * so a dot product between two raw vectors is not a cosine and a fixed distance threshold would drift
 * with image brightness. Normalising once, on the way out, is what makes the clusterer's comparison a
 * plain dot product and the threshold a cosine distance that can be calibrated.
 *
 * Not thread-safe; one instance per indexing worker.
 */
class GhostFaceNetEmbedder(modelPath: String) : FaceEmbedder {

    private val interpreter: Interpreter = Interpreter(File(modelPath), Interpreter.Options())

    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(INPUT_SIZE * INPUT_SIZE * CHANNELS * BYTES_PER_FLOAT)
        .order(ByteOrder.nativeOrder())

    /** One 512-float output row. */
    private val outputBuffer: Array<Array<FloatArray>> = Array(1) { Array(1) { FloatArray(FaceEmbeddingCodec.DIMENSIONS) } }

    override fun embed(alignedFace: Bitmap): FloatArray {
        require(alignedFace.width == INPUT_SIZE && alignedFace.height == INPUT_SIZE) {
            "expected a ${INPUT_SIZE}x$INPUT_SIZE aligned face, got ${alignedFace.width}x${alignedFace.height}"
        }

        fillInput(alignedFace)
        interpreter.run(inputBuffer, outputBuffer)

        val embedding = outputBuffer[0][0].copyOf()
        // Normalise before returning: see the class KDoc. A zero vector stays zero, which the codec
        // documents as the honest outcome for an embedding carrying no information.
        FaceEmbeddingCodec.normaliseInPlace(embedding)
        return embedding
    }

    override fun close() {
        interpreter.close()
    }

    /**
     * Writes the aligned face as **RGB** float32 in the network's expected range.
     *
     * Bitmap pixels are ARGB ints, so the red and blue channels are swapped relative to the network's
     * input order — see the class KDoc for what that costs if forgotten.
     */
    private fun fillInput(bitmap: Bitmap) {
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        inputBuffer.rewind()
        for (pixel in pixels) {
            inputBuffer.putFloat((((pixel shr 16) and 0xFF) - PIXEL_MEAN) * PIXEL_SCALE)
            inputBuffer.putFloat((((pixel shr 8) and 0xFF) - PIXEL_MEAN) * PIXEL_SCALE)
            inputBuffer.putFloat(((pixel and 0xFF) - PIXEL_MEAN) * PIXEL_SCALE)
        }
        inputBuffer.rewind()
    }

    private companion object {
        const val INPUT_SIZE = 112
        const val CHANNELS = 3
        const val BYTES_PER_FLOAT = 4

        /** Upstream's preprocessing constants. See `data.py`. */
        const val PIXEL_MEAN = 127.5f
        const val PIXEL_SCALE = 0.0078125f
    }
}
