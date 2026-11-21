package com.telepic.data.face

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts between a face embedding and the bytes stored in `face.embedding`.
 *
 * The format is little-endian float32, which is what [ByteBuffer] gives on every Android ABI the app
 * ships (ARM is little-endian). It is fixed rather than platform-default so a database copied between
 * devices — or written by a test on a big-endian JVM — still decodes identically.
 *
 * Embeddings are **L2-normalised** before they are stored. GhostFaceNet's raw output has a norm around
 * 13, so a dot product between two raw vectors is not a cosine and a fixed distance threshold would
 * drift with image brightness. Normalising once at write time means clustering is a plain dot product
 * and the threshold is a cosine distance the calibration script can talk about directly.
 */
object FaceEmbeddingCodec {

    /** GhostFaceNet V1 W1.3-S1 emits 512 floats. */
    const val DIMENSIONS = 512

    private const val BYTES_PER_FLOAT = 4

    /** The size of one stored embedding, in bytes. */
    const val BYTE_COUNT = DIMENSIONS * BYTES_PER_FLOAT

    fun encode(embedding: FloatArray): ByteArray {
        require(embedding.size == DIMENSIONS) {
            "expected $DIMENSIONS values, got ${embedding.size}"
        }
        return ByteBuffer.allocate(BYTE_COUNT)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { embedding.forEach(::putFloat) }
            .array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size == BYTE_COUNT) {
            "expected $BYTE_COUNT bytes, got ${bytes.size}"
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(DIMENSIONS) { buffer.getFloat() }
    }

    /**
     * Scales [values] to unit length in place.
     *
     * A zero vector cannot be normalised and would otherwise become NaN, which poisons every distance
     * it touches and makes the clusterer produce garbage rather than skip one face. Such a vector is
     * left as zeros: its cosine against anything is 0, so it lands in its own singleton group, which
     * is the honest outcome for an embedding that carries no information.
     */
    fun normaliseInPlace(values: FloatArray) {
        var sumSquares = 0.0
        for (value in values) sumSquares += value.toDouble() * value
        if (sumSquares <= 0.0) return
        val inverse = (1.0 / kotlin.math.sqrt(sumSquares)).toFloat()
        for (i in values.indices) values[i] *= inverse
    }

    /**
     * Cosine similarity of two already-normalised embeddings, in `-1..1`.
     *
     * Clamped because float accumulation can overshoot 1.0 by a few ULPs, and a similarity above 1
     * makes the derived distance negative — which would let an arbitrarily bad threshold look fine.
     */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "dimension mismatch: ${a.size} vs ${b.size}" }
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i]
        return dot.toFloat().coerceIn(-1f, 1f)
    }
}
